package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig

internal object SubscriptionSchedulePolicy {
    const val RESCHEDULE_FLOOR_MILLIS = 5_000L

    fun isSuccessfulRefresh(importedConfigCount: Int): Boolean = importedConfigCount > 0

    fun normalizeIntervalMinutes(requestedMinutes: Long): Long =
        maxOf(AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES, requestedMinutes)

    fun calculateInitialDelayMillis(
        lastSuccessfulUpdateMillis: Long,
        intervalMinutes: Long,
        nowMillis: Long,
        forceReschedule: Boolean
    ): Long {
        val intervalMillis = normalizeIntervalMinutes(intervalMinutes) * 60 * 1_000L
        val remainingMillis = if (lastSuccessfulUpdateMillis <= 0L) {
            0L
        } else {
            maxOf(0L, lastSuccessfulUpdateMillis + intervalMillis - nowMillis)
        }

        return if (forceReschedule) {
            maxOf(RESCHEDULE_FLOOR_MILLIS, remainingMillis)
        } else {
            remainingMillis
        }
    }
}
