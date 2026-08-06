package com.v2ray.ang.ui.account

internal object AccountRefreshPolicy {
    const val MIN_REFRESH_INTERVAL_MILLIS = 5 * 60 * 1000L

    fun shouldRefresh(
        force: Boolean,
        authenticated: Boolean,
        hasSnapshot: Boolean,
        lastRefreshElapsedMillis: Long,
        nowElapsedMillis: Long,
    ): Boolean {
        if (!authenticated) return false
        if (force || !hasSnapshot || lastRefreshElapsedMillis <= 0L) return true
        return nowElapsedMillis - lastRefreshElapsedMillis >= MIN_REFRESH_INTERVAL_MILLIS
    }
}
