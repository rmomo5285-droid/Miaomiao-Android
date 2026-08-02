package com.v2ray.ang.xboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XBoardOrderPolicyTest {
    @Test
    fun prefersPersistedRecoverableOrder() {
        val orders = listOf(
            XBoardOrderRecord(id = 2, tradeNo = "new", status = 1, createdAt = 200),
            XBoardOrderRecord(id = 1, tradeNo = "persisted", status = 0, createdAt = 100),
        )

        assertEquals(
            "persisted",
            XBoardOrderPolicy.findRecoverable(orders, "persisted")?.tradeNo,
        )
    }

    @Test
    fun picksNewestPendingOrProcessingAndRejectsTerminalOrders() {
        val orders = listOf(
            XBoardOrderRecord(id = 1, tradeNo = "completed", status = 3, createdAt = 300),
            XBoardOrderRecord(id = 2, tradeNo = "pending", status = 0, createdAt = 100),
            XBoardOrderRecord(id = 3, tradeNo = "processing", status = 1, createdAt = 200),
            XBoardOrderRecord(id = 4, tradeNo = "canceled", status = 2, createdAt = 400),
        )

        assertEquals("processing", XBoardOrderPolicy.findRecoverable(orders)?.tradeNo)
        assertNull(
            XBoardOrderPolicy.findRecoverable(
                orders.filter { it.status == 2 || it.status == 3 },
            ),
        )
    }

    @Test
    fun mapsXBoardOrderStates() {
        assertEquals(XBoardPaymentState.PENDING, XBoardOrderPolicy.paymentState(0))
        assertEquals(XBoardPaymentState.PROCESSING, XBoardOrderPolicy.paymentState(1))
        assertEquals(XBoardPaymentState.CANCELED, XBoardOrderPolicy.paymentState(2))
        assertEquals(XBoardPaymentState.COMPLETED, XBoardOrderPolicy.paymentState(3))
        assertEquals(XBoardPaymentState.COMPLETED, XBoardOrderPolicy.paymentState(4))
        assertEquals(XBoardPaymentState.UNKNOWN, XBoardOrderPolicy.paymentState(null))
    }
}
