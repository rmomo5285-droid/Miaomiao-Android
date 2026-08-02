package com.v2ray.ang.ui.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingPaymentPolicyTest {
    @Test
    fun submittedCheckoutRemainsNonRepeatableWhileXBoardStatusIsPending() {
        val phase = PendingPaymentPolicy.phaseAfterOrderRefresh(
            orderStatus = 0,
            paymentId = null,
            persistedPhase = PendingPaymentPhase.CHECKOUT_SUBMITTED,
        )

        assertEquals(PendingPaymentPhase.CHECKOUT_SUBMITTED, phase)
        assertFalse(PendingPaymentPolicy.canSubmitCheckout(PendingPayment("trade", phase)))
        assertEquals(
            PendingPaymentPhase.CHECKOUT_SUBMITTED,
            PendingPaymentPolicy.phaseAfterOrderRefresh(
                orderStatus = 0,
                paymentId = 7,
                persistedPhase = PendingPaymentPhase.ORDER_CREATED,
            ),
        )
    }

    @Test
    fun newOrderCanCheckoutAndProcessingOrderCannot() {
        assertTrue(
            PendingPaymentPolicy.canSubmitCheckout(
                PendingPayment("new", PendingPaymentPhase.ORDER_CREATED),
            ),
        )
        assertEquals(
            PendingPaymentPhase.CHECKOUT_SUBMITTED,
            PendingPaymentPolicy.phaseAfterOrderRefresh(
                orderStatus = 1,
                paymentId = null,
                persistedPhase = PendingPaymentPhase.ORDER_CREATED,
            ),
        )
    }
}
