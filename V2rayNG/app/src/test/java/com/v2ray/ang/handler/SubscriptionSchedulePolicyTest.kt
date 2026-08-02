package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.SubscriptionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionSchedulePolicyTest {

    @Test
    fun newSubscriptionDefaultsToAutomatic48HourUpdates() {
        val subscription = SubscriptionItem()

        assertTrue(subscription.autoUpdate)
        assertEquals(2_880L, subscription.updateInterval)
    }

    @Test
    fun intervalIsClampedToWorkManagerMinimum() {
        assertEquals(
            AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES,
            SubscriptionSchedulePolicy.normalizeIntervalMinutes(1L)
        )
    }

    @Test
    fun refreshSucceedsOnlyWhenUsableConfigsWereImported() {
        assertFalse(SubscriptionSchedulePolicy.isSuccessfulRefresh(-1))
        assertFalse(SubscriptionSchedulePolicy.isSuccessfulRefresh(0))
        assertTrue(SubscriptionSchedulePolicy.isSuccessfulRefresh(1))
    }

    @Test
    fun neverUpdatedSubscriptionCanRunImmediately() {
        val delay = SubscriptionSchedulePolicy.calculateInitialDelayMillis(
            lastSuccessfulUpdateMillis = -1L,
            intervalMinutes = AppConfig.SUBSCRIPTION_DEFAULT_INTERVAL_MINUTES,
            nowMillis = 100_000L,
            forceReschedule = false
        )

        assertEquals(0L, delay)
    }

    @Test
    fun recentSuccessfulUpdateWaitsForRemainingInterval() {
        val now = 10_000_000L
        val elapsed = 60_000L
        val intervalMillis = 2_880L * 60 * 1_000L

        val delay = SubscriptionSchedulePolicy.calculateInitialDelayMillis(
            lastSuccessfulUpdateMillis = now - elapsed,
            intervalMinutes = 2_880L,
            nowMillis = now,
            forceReschedule = false
        )

        assertEquals(intervalMillis - elapsed, delay)
    }

    @Test
    fun expiredSubscriptionCanRunImmediately() {
        val now = 200_000_000L
        val intervalMillis = 2_880L * 60 * 1_000L

        val delay = SubscriptionSchedulePolicy.calculateInitialDelayMillis(
            lastSuccessfulUpdateMillis = now - intervalMillis - 1L,
            intervalMinutes = 2_880L,
            nowMillis = now,
            forceReschedule = false
        )

        assertEquals(0L, delay)
    }

    @Test
    fun forcedRescheduleUsesLoopPreventionFloor() {
        val delay = SubscriptionSchedulePolicy.calculateInitialDelayMillis(
            lastSuccessfulUpdateMillis = -1L,
            intervalMinutes = 2_880L,
            nowMillis = 100_000L,
            forceReschedule = true
        )

        assertEquals(SubscriptionSchedulePolicy.RESCHEDULE_FLOOR_MILLIS, delay)
    }
}
