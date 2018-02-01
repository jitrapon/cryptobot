package io.jitrapon.cryptobot

import java.math.BigDecimal

data class Trade(val rate: BigDecimal,
                 val amount: BigDecimal,
                 val type: String,
                 val time: String)