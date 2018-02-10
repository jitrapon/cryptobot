package io.jitrapon.cryptobot

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.util.StatusPrinter
import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.LocalDateTime
import java.util.*

class Server : AbstractVerticle() {

    private lateinit var logger: Logger

    /* http client */
    private lateinit var client: WebClient

    /* all the currency pairs available for trade */
    private val currencyPairs = HashMap<String, CurrencyPairInfo>()

    /* last known maximum rate when in sell mode, last saved rate when in buy mode */
    private var lastRate: BigDecimal? = null

    /* current rate */
    private var currentRate: BigDecimal? = null

    /* time at which lastKnownMaxRate was saved */
    private var lastRateSaveTime: LocalDateTime? = null

    /* amount of unit of primary currency */
    private var primaryBalance = BigDecimal(40000.00000000)

    /* amount of unit of secondary currency */
    private var secondaryBalance = BigDecimal(0.00000000)

    /* primary currency traded */
    private lateinit var primaryCurrency: String

    /* secondary currency traded */
    private lateinit var secondaryCurrency: String

    /* whether or not this bot is in buy or sell mode */
    private var isInBuyMode: Boolean = true

    companion object {
        private const val HOST = "https://bx.in.th/api"

        /* amount in percentage gained after transaction fee has been deducted */
        private val TRANSACTION_FEE_MULTIPLIER = BigDecimal(0.9975)

        /*
         * SUBJECT TO CHANGE time it takes until next fetch
         */
        private val FETCH_INTERVAL = Duration.ofMinutes(3)

        /* SUBJECT TO CHANGE time after which it is possible to send a sell order if the sell percentage meets,
         * this is also the time interval when currentRate, lastRate (in sell mode), and lastRateSaveTime are updated.
          * Usually, it is safer to set this time interval to be less than the BUY_TIME_INTERVAL */
        private val SELL_TIME_INTERVAL = Duration.ofMinutes(30) //FIXME change to 30 minutes

        /* SUBJECT TO CHANGE time after which it is possible to send a buy order if the buy percentage meets */
        private val BUY_TIME_INTERVAL = Duration.ofHours(1) //FIXME change to 1 hour

        /* if the percentage difference between lastRate and current trade rate exceeds this percentage after BUY_TIME_INTERVAL, a buy order will be sent */
        private val MIN_BUY_PERCENT = BigDecimal(3) //FIXME change to 3%

        /* if the percentage difference between lastRate and current trade rate falls below this percentage after SELL_TIME_INTERVAL,
            a sell order will be sent
         */
        private val MIN_SELL_PERCENT = BigDecimal(-1.5) //FIXME change to -1.5%
    }

    private fun initializeLogging() {
        // assume SLF4J is bound to logback in the current environment
        val lc = LoggerFactory.getILoggerFactory() as LoggerContext
        // print logback's internal status
        StatusPrinter.print(lc)
        logger = LoggerFactory.getLogger(this.javaClass)
    }

    private fun logState() {
        logger.info("mode=${if (isInBuyMode) "BUY" else "SELL"}, " +
                "primaryBalance=$primaryBalance $primaryCurrency, secondaryBalance=$secondaryBalance $secondaryCurrency")
    }

    private fun logRateChange(currentTime: LocalDateTime, percentDiff: BigDecimal) {
        logger.info("percentChange=$percentDiff lastRate=$lastRate, lastRateSaveTime=$lastRateSaveTime, currentRate=$currentRate, currentTime=$currentTime")
    }

    private fun logError(throwable: Throwable) {
        logger.error(throwable.message)
    }

    override fun start(future: Future<Void>) {
        initializeLogging()

        isInBuyMode = true

        // initialize httpclient
        val clientOptions = WebClientOptions()
                .setSsl(true)
                .setDefaultPort(443)
                .setDefaultHost(HOST)
                .setTrustAll(true)
                .setKeepAlive(false)
        client = WebClient.create(vertx, clientOptions)

        // start trading
        loadMarketData {
            trade("thb", "eth")
        }
    }

    private fun loadMarketData(onComplete: () -> Unit) {
        logger.info("Fetching market data...")

        // get all current pairings
        client.getAbs("$HOST/pairing")
                .send {
                    if (it.succeeded()) {
                        val response = it.result()
                        response?.bodyAsJsonObject()?.forEach {
                            (it.value as JsonObject).let {
                                val primary = it.getString("primary_currency").toLowerCase()
                                val secondary = it.getString("secondary_currency").toLowerCase()
                                if (it.getBoolean("active")) {
                                    currencyPairs[primary + secondary] = CurrencyPairInfo(
                                            it.getString("pairing_id"),
                                            primary,
                                            secondary)
                                }
                            }
                        }

                        logger.info("Found ${currencyPairs.size} currency pairs")
                        onComplete()
                    }
                    else logError(it.cause())
                }
    }

    private fun trade(primary: String, secondary: String) {
        logger.info("Begin trading $primary - $secondary")

        primaryCurrency = primary
        secondaryCurrency = secondary
        val currencyPair: String = primaryCurrency + secondaryCurrency
        currencyPair.toLowerCase().let {
            currencyPairs[it].let {
                if (it == null) {
                    logger.warn("No currency pair '$primaryCurrency to $secondaryCurrency' found on this exchange")
                    return
                }
                else {
                    val id = it.id
                    fetchTrade(id)
                    vertx.setPeriodic(FETCH_INTERVAL.toMillis(), {
                        fetchTrade(id)
                    })
                }
            }
        }
    }

    private fun fetchTrade(id: String?) {
        val fetchTime = LocalDateTime.now()
        logger.debug("Fetching recent trades --->")

        id ?: return
        client.getAbs("$HOST/trade/?pairing=$id")
                .send {
                    val responseTime = Duration.between(fetchTime, LocalDateTime.now())
                    logger.debug("<--- [${it.result()?.statusCode()}] Receiving trade data, request took ${responseTime.toMillis()} ms")

                    if (it.succeeded()) {
                        try {
                            it.result()?.let {
                                it.bodyAsJsonObject()?.forEach {
                                    val key = it.key
                                    (it.value as JsonArray).let {
                                        if (key == "trades") {
                                            (it.last() as? JsonObject)?.let {
                                                processTrade(Trade(
                                                        BigDecimal(it.getString("rate")),
                                                        BigDecimal(it.getString("amount")),
                                                        it.getString("trade_type"),
                                                        it.getString("trade_date")
                                                ))
                                                return@forEach
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        catch (ex: Exception) {
                            logError(ex)
                        }
                    }
                    else logError(it.cause())
                }
    }

    /**
     * TODO find out buy/sell volume so far
     * TODO trade different pair on different accounts better ? (i.e. THBBTC / THBETC / THBOMG)
     * TODO buy price = slightly higher than trade.rate, sell price = sligohtly lower
     * TODO find percentage change every minute and keep track of max price so far
     * TODO different timeframe for buy an sell
     * TODO sell with different timeframe and percentages for different account
     */
    private fun processTrade(trade: Trade) {
        currentRate = trade.rate
        val now = LocalDateTime.now()
        if (lastRate != currentRate) {
            lastRate?.let {
                val elapsedTime = Duration.between(lastRateSaveTime, now)
                val percentDiff = ((currentRate!! - it) / it) * BigDecimal(100)

                // if in buy mode, if BUY_TIME_INTERVAL has passed, check if the percentage difference between
                // lastRate and currentRate exceeds minBuyPercentage
                if (isInBuyMode) {
                    if (elapsedTime >= BUY_TIME_INTERVAL) {
                        logger.info("Elapsed time exceeds BUY_TIME_INTERVAL")

                        // adjust lastPrice to be current price if percentDiff does not exceed buy percentage
                        when {
                            percentDiff >= MIN_BUY_PERCENT -> {
                                logger.info("BUY CRITERIA percentDiff >= MIN_BUY_PERCENT SATISFIED! ($percentDiff >= $MIN_BUY_PERCENT)")
                                logRateChange(now, percentDiff)

                                // create buy order
                                createOrder(true, primaryBalance, currentRate!!) {
                                    lastRateSaveTime = LocalDateTime.now()
                                    lastRate = currentRate
                                    isInBuyMode = false

                                    logState()
                                    logRateChange(LocalDateTime.now(), BigDecimal(0))
                                }
                            }
                            currentRate!! < it -> {
                                logger.info("CRITERIA currentRate < lastRate met. Updating lastRate to be $currentRate...")
                                logRateChange(now, percentDiff)

                                lastRateSaveTime = now
                                lastRate = currentRate
                            }
                            else -> {
                                logger.info("Rate has risen but has not reached MIN_BUY_PERCENT of $MIN_BUY_PERCENT yet")
                                logRateChange(now, percentDiff)
                            }
                        }
                    }
                }

                // if in sell mode, if SELL_TIME_INTERVAL has passed, update lastPrice to be the current
                // maximum price seen so far. Also check if the percentage difference between lastRate
                else {
                    if (elapsedTime >= SELL_TIME_INTERVAL) {

                        // if the new price is higher than our last price bought, we hold
                        // and update the new price
                        if (currentRate!! > it) {
                            logger.info("CRITERIA currentRate > lastRate met. Holding...")
                            logRateChange(now, percentDiff)

                            lastRateSaveTime = now
                            lastRate = currentRate
                        }

                        // however, if the percentDiff is negative, and it is lower than
                        // MIN SELL PERCENTAGE, start selling
                        else if (percentDiff < MIN_SELL_PERCENT) {
                            logger.info("SELL CRITERIA percentDiff < MIN_SELL_PERCENT SATISFIED! ($percentDiff < $MIN_SELL_PERCENT)")
                            logRateChange(now, percentDiff)

                            // create sell order
                            createOrder(false, secondaryBalance, currentRate!!) {
                                lastRateSaveTime = LocalDateTime.now()
                                lastRate = currentRate
                                isInBuyMode = true

                                logState()
                                logRateChange(LocalDateTime.now(), BigDecimal(0))
                            }
                        }
                    }
                }
            }

            // this will happen if the program is first loaded.
            // last rate is first initialized
            if (lastRate == null) {
                lastRate = currentRate
                lastRateSaveTime = now

                logState()
                logRateChange(now, BigDecimal(0))
            }
        }
    }

    private fun createOrder(buy: Boolean, amount: BigDecimal, rate: BigDecimal, onComplete: () -> Unit) {
        val roundedAmount = amount.setScale(8, RoundingMode.HALF_UP)
        if (buy) {
            logger.info("Buying amount of $amount $primaryCurrency at rate $rate")
            // perform transaction...
            primaryBalance -= roundedAmount
            secondaryBalance += (roundedAmount * TRANSACTION_FEE_MULTIPLIER / rate).setScale(8, RoundingMode.HALF_UP)
        }
        else {
            logger.info("Selling amount of $roundedAmount $secondaryCurrency at rate $rate")
            // perform transaction...
            secondaryBalance -= roundedAmount
            primaryBalance += (roundedAmount * TRANSACTION_FEE_MULTIPLIER * rate).setScale(8, RoundingMode.HALF_UP)
        }
        onComplete()
    }
}