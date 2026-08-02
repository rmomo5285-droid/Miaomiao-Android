package com.v2ray.ang.ui.account

internal object PaymentPollingPolicy {
    const val INTERVAL_MILLIS = 5_000L
    const val MAX_ATTEMPTS = 120
    const val MAX_DURATION_MILLIS = INTERVAL_MILLIS * MAX_ATTEMPTS
    const val MAX_STATUS_REQUEST_MILLIS = 20_000L

    fun remainingMillis(startedAtMillis: Long, nowMillis: Long): Long =
        (MAX_DURATION_MILLIS - (nowMillis - startedAtMillis).coerceAtLeast(0L))
            .coerceAtLeast(0L)
}
