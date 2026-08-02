package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class VpnMtuPolicyTest {
    @Test
    fun defaultsTo1280ForMissingMalformedOrOutOfRangeValues() {
        listOf(null, "", "invalid", "0", "1279", "9001", "999999999999").forEach { value ->
            assertEquals(AppConfig.VPN_MTU, VpnMtuPolicy.normalize(value))
        }
    }

    @Test
    fun preservesValidMtuValues() {
        assertEquals(1280, VpnMtuPolicy.normalize("1280"))
        assertEquals(1408, VpnMtuPolicy.normalize("1408"))
        assertEquals(9000, VpnMtuPolicy.normalize("9000"))
    }
}
