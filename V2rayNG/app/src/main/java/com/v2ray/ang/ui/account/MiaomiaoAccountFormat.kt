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

internal fun accountTrafficGigabytes(bytes: Long): Double {
    if (bytes <= 0L) return 0.0
    return if (bytes >= LEGACY_SCALE_THRESHOLD_BYTES) {
        bytes / (BYTES_PER_GIGABYTE * 1024.0)
    } else {
        bytes / BYTES_PER_GIGABYTE
    }
}

internal fun formatAccountGigabytes(
    gigabytes: Double,
    locale: Locale = Locale.getDefault(),
): String = String.format(
    locale,
    if (gigabytes >= 100.0) "%.1f GB" else "%.2f GB",
    gigabytes.coerceAtLeast(0.0),
)

internal fun formatAccountTrafficBytes(
    bytes: Long,
    locale: Locale = Locale.getDefault(),
): String = formatAccountGigabytes(accountTrafficGigabytes(bytes), locale)

private const val GIGABYTES_PER_TERABYTE = 1024L
private const val BYTES_PER_GIGABYTE = 1024.0 * 1024.0 * 1024.0
private const val LEGACY_SCALE_THRESHOLD_BYTES = BYTES_PER_GIGABYTE * 1024.0 * 64.0
