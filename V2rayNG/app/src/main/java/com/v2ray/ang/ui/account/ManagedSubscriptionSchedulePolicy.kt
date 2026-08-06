package com.v2ray.ang.ui.account

import com.v2ray.ang.dto.entities.SubscriptionItem

internal object ManagedSubscriptionSchedulePolicy {
    fun shouldReschedule(
        existing: SubscriptionItem?,
        url: String,
        intervalMinutes: Long,
    ): Boolean {
        return existing == null ||
            existing.url != url ||
            !existing.enabled ||
            !existing.autoUpdate ||
            existing.updateInterval != intervalMinutes
    }
}
