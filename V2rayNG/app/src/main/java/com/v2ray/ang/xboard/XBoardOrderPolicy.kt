package com.v2ray.ang.xboard

internal enum class XBoardPaymentState {
    PENDING,
    PROCESSING,
    CANCELED,
    COMPLETED,
    UNKNOWN,
}

internal object XBoardOrderPolicy {
    fun paymentState(status: Int?): XBoardPaymentState = when (status) {
        0 -> XBoardPaymentState.PENDING
        1 -> XBoardPaymentState.PROCESSING
        2 -> XBoardPaymentState.CANCELED
        3, 4 -> XBoardPaymentState.COMPLETED
        else -> XBoardPaymentState.UNKNOWN
    }

    fun findRecoverable(
        orders: List<XBoardOrderRecord>,
        preferredTradeNo: String? = null,
    ): XBoardOrderRecord? {
        val recoverable = orders.filter {
            it.tradeNo.isNotBlank() &&
                it.tradeNo.length <= MAX_TRADE_NO_CHARS &&
                paymentState(it.status) in setOf(
                    XBoardPaymentState.PENDING,
                    XBoardPaymentState.PROCESSING,
                )
        }
        preferredTradeNo
            ?.let { preferred -> recoverable.firstOrNull { it.tradeNo == preferred } }
            ?.let { return it }
        return recoverable.maxWithOrNull(
            compareBy<XBoardOrderRecord> { it.createdAt ?: Long.MIN_VALUE }
                .thenBy { it.id ?: Int.MIN_VALUE },
        )
    }

    private const val MAX_TRADE_NO_CHARS = 128
}
