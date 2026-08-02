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
}
