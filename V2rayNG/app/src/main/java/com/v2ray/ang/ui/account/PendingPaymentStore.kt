package com.v2ray.ang.ui.account

import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager

internal enum class PendingPaymentPhase {
    ORDER_CREATED,
    CHECKOUT_SUBMITTED,
}

internal data class PendingPayment(
    val tradeNo: String,
    val phase: PendingPaymentPhase,
)

internal object PendingPaymentPolicy {
    fun canSubmitCheckout(payment: PendingPayment): Boolean =
        payment.phase == PendingPaymentPhase.ORDER_CREATED

    fun phaseAfterOrderRefresh(
        orderStatus: Int?,
        paymentId: Int?,
        persistedPhase: PendingPaymentPhase?,
    ): PendingPaymentPhase = if (orderStatus == 1 || (paymentId ?: 0) > 0) {
        PendingPaymentPhase.CHECKOUT_SUBMITTED
    } else {
        persistedPhase ?: PendingPaymentPhase.ORDER_CREATED
    }
}

internal interface PendingPaymentStore {
    fun read(): PendingPayment?
    fun write(payment: PendingPayment)
    fun clear()
}

internal object MmkvPendingPaymentStore : PendingPaymentStore {
    override fun read(): PendingPayment? {
        val tradeNo = MmkvManager.decodeSettingsString(AppConfig.PREF_MIAOMIAO_PENDING_TRADE_NO)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_TRADE_NO_CHARS }
            ?: return null
        val phase = MmkvManager.decodeSettingsString(
            AppConfig.PREF_MIAOMIAO_PENDING_PAYMENT_PHASE,
        )?.let { stored ->
            runCatching { PendingPaymentPhase.valueOf(stored) }.getOrNull()
        } ?: PendingPaymentPhase.ORDER_CREATED
        return PendingPayment(tradeNo, phase)
    }

    override fun write(payment: PendingPayment) {
        require(payment.tradeNo.isNotBlank() && payment.tradeNo.length <= MAX_TRADE_NO_CHARS)
        // Persist the no-replay phase first so a process death cannot downgrade checkout state.
        check(
            MmkvManager.encodeSettings(
                AppConfig.PREF_MIAOMIAO_PENDING_PAYMENT_PHASE,
                payment.phase.name,
            ),
        )
        check(MmkvManager.encodeSettings(AppConfig.PREF_MIAOMIAO_PENDING_TRADE_NO, payment.tradeNo))
    }

    override fun clear() {
        MmkvManager.encodeSettings(AppConfig.PREF_MIAOMIAO_PENDING_TRADE_NO, "")
        MmkvManager.encodeSettings(AppConfig.PREF_MIAOMIAO_PENDING_PAYMENT_PHASE, "")
    }

    private const val MAX_TRADE_NO_CHARS = 128
}
