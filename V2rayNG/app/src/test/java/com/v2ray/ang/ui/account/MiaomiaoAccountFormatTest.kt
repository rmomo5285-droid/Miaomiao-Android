package com.v2ray.ang.ui.account

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class MiaomiaoAccountFormatTest {
    @Test
    fun planTransferUsesXBoardGigabyteUnits() {
        assertEquals("500 GB", formatPlanTransferGigabytes(500, Locale.US))
        assertEquals("1.00 TB", formatPlanTransferGigabytes(1024, Locale.US))
        assertEquals("1.50 TB", formatPlanTransferGigabytes(1536, Locale.US))
    }

    @Test
    fun accountTrafficAlwaysDisplaysInGigabytes() {
        val bytesPerGigabyte = 1024L * 1024L * 1024L
        assertEquals("800.0 GB", formatAccountTrafficBytes(800L * bytesPerGigabyte, Locale.US))
        assertEquals(
            "800.0 GB",
            formatAccountTrafficBytes(800L * bytesPerGigabyte * 1024L, Locale.US),
        )
        assertEquals("1.50 GB", formatAccountTrafficBytes(bytesPerGigabyte + bytesPerGigabyte / 2L, Locale.US))
    }
}
