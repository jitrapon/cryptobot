package io.jitrapon.cryptobot

import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.LocalDateTime
import java.util.*

class Server : AbstractVerticle() {

    private var logger = LoggerFactory.getLogger(this.javaClass)
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
    private var primaryBalance = BigDecimal(1000.00000000)

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

    override fun start(future: Future<Void>) {
        isInBuyMode = true

        // initialize httpclient
        val clientOptions = WebClientOptions()
                .setSsl(true)
                .setDefaultPort(443)
                .setDefaultHost(HOST)
                .setTrustAll(true)
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
                    else {
                        logger.error("Something went wrong ${it.cause().message}")
                    }
                }
    }

    private fun trade(primary: String, secondary: String) {
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
                    vertx.setPeriodic(SELL_TIME_INTERVAL.toMillis(), {
                        fetchTrade(id)
                    })
                }
            }
        }
    }

    private fun fetchTrade(id: String?) {
        id ?: return
        client.getAbs("$HOST/trade/?pairing=$id")
                .send {
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
                            logger.error("Something went wrong", ex)
                        }
                    }
                    else {
                        logger.error("Something went wrong", it)
                    }
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
        if (lastRate != currentRate) {
            lastRate?.let {
                val elapsedTime = Duration.between(lastRateSaveTime, LocalDateTime.now())
                val percentDiff = ((currentRate!! - it) / it) * BigDecimal(100)

                // if in buy mode, if BUY_TIME_INTERVAL has passed, check if the percentage difference between
                // lastRate and currentRate exceeds minBuyPercentage
                if (isInBuyMode) {
                    if (elapsedTime >= BUY_TIME_INTERVAL) {

                        // adjust lastPrice to be current price if percentDiff does not exceed buy percentage
                        if (percentDiff >= MIN_BUY_PERCENT) {
                            logger.info("New rate is $currentRate ($percentDiff% change). Rising above minimum threshold of $MIN_BUY_PERCENT")
                            createOrder(true, primaryBalance, currentRate!!) {
                                logger.info("SELL MODE ACTIVATED")
                                lastRateSaveTime = LocalDateTime.now()
                                lastRate = currentRate
                                isInBuyMode = false
                            }
                        }
                        else if (currentRate!! < it) {
                            logger.info("Last rate was $lastRate, ${elapsedTime?.seconds} seconds ago. " +
                                    "Rate is now $currentRate ($percentDiff%), updating last rate")
                            lastRateSaveTime = LocalDateTime.now()
                            lastRate = currentRate
                        }
                        else {
                            logger.info("Last rate was $lastRate, ${elapsedTime?.seconds} seconds ago. " +
                                    "Rate is now $currentRate ($percentDiff%)")
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
                            logger.info("Found higher rate ($currentRate) than last rate ($it), holding...")
                            lastRateSaveTime = LocalDateTime.now()
                            lastRate = currentRate
                        }

                        // however, if the percentDiff is negative, and it is lower than
                        // MIN SELL PERCENTAGE, start selling
                        else if (percentDiff < MIN_SELL_PERCENT) {
                            logger.info("Dropping below minimum threshold of $MIN_SELL_PERCENT")
                            createOrder(false, secondaryBalance, currentRate!!) {
                                logger.info("BUY MODE ACTIVATED")
                                lastRateSaveTime = LocalDateTime.now()
                                lastRate = currentRate
                                isInBuyMode = true
                            }
                        }
                    }
                }
            }

            // this will happen if the program is first loaded.
            // last rate is first initialized
            if (lastRate == null) {
                lastRate = currentRate
                lastRateSaveTime = LocalDateTime.now()
                logger.info("Current rate is $currentRate. Time is now $lastRateSaveTime. Mode is BUY? $isInBuyMode")
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
            logger.info("Balance is now $primaryBalance $primaryCurrency / $secondaryBalance $secondaryCurrency")
        }
        else {
            logger.info("Selling amount of $roundedAmount $secondaryCurrency at rate $rate")
            // perform transaction...
            secondaryBalance -= roundedAmount
            primaryBalance += (roundedAmount * TRANSACTION_FEE_MULTIPLIER * rate).setScale(8, RoundingMode.HALF_UP)
            logger.info("Balance is now $primaryBalance $primaryCurrency / $secondaryBalance $secondaryCurrency")
        }
        onComplete()
    }
}