package com.v2ray.ang.ui.account

import java.util.Locale

internal fun formatPlanTransferGigabytes(
    gigabytes: Long,
    locale: Locale = Locale.getDefault(),
): String {
    val safeGigabytes = gigabytes.coerceAtLeast(0L)
    return if (safeGigabytes >= GIGABYTES_PER_TERABYTE) {
        String.format(locale, "%.2f TB", safeGigabytes / GIGABYTES_PER_TERABYTE.toDouble())
    } else {
        String.format(locale, "%d GB", safeGigabytes)
    }
}

private const val GIGABYTES_PER_TERABYTE = 1024L
