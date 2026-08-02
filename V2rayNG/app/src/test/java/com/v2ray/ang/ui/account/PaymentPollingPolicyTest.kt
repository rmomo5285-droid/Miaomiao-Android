package com.v2ray.ang.ui.account

import org.junit.Assert.assertEquals
import org.junit.Test

class PaymentPollingPolicyTest {
    @Test
    fun pollingRunsEveryFiveSecondsForAtMostTenMinutes() {
        assertEquals(5_000L, PaymentPollingPolicy.INTERVAL_MILLIS)
        assertEquals(120, PaymentPollingPolicy.MAX_ATTEMPTS)
        assertEquals(10L * 60L * 1_000L, PaymentPollingPolicy.MAX_DURATION_MILLIS)
        assertEquals(20_000L, PaymentPollingPolicy.MAX_STATUS_REQUEST_MILLIS)
        assertEquals(1L, PaymentPollingPolicy.remainingMillis(100L, 600_099L))
        assertEquals(0L, PaymentPollingPolicy.remainingMillis(100L, 600_100L))
        assertEquals(0L, PaymentPollingPolicy.remainingMillis(100L, 900_000L))
    }
}
