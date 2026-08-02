package com.v2ray.ang.fmt

import com.v2ray.ang.core.CoreOutboundBuilder
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Hysteria2FmtTest {

    @Test
    fun parseAndSerialize_preservesManagedSubscriptionFields() {
        val source = "hysteria2://secret@example.com:443" +
            "?insecure=1&sni=edge.example.com&obfs=salamander" +
            "&obfs-password=mask&mport=40000-50000&pinSHA256=abc123#HY2"

        val profile = Hysteria2Fmt.parse(source)

        assertEquals(EConfigType.HYSTERIA2, profile.configType)
        assertEquals(NetworkType.HYSTERIA.type, profile.network)
        assertEquals("example.com", profile.server)
        assertEquals("443", profile.serverPort)
        assertEquals("secret", profile.password)
        assertEquals("edge.example.com", profile.sni)
        assertEquals("mask", profile.obfsPassword)
        assertEquals("40000-50000", profile.portHopping)
        assertEquals("abc123", profile.pinnedCA256)
        assertEquals(true, profile.insecure)

        val serialized = Hysteria2Fmt.toUri(profile)
        assertFalse(serialized.startsWith("hysteria2://"))
        val reparsed = Hysteria2Fmt.parse("hysteria2://$serialized")
        assertEquals(profile.server, reparsed.server)
        assertEquals(profile.serverPort, reparsed.serverPort)
        assertEquals(profile.password, reparsed.password)
        assertEquals(profile.sni, reparsed.sni)
        assertEquals(profile.obfsPassword, reparsed.obfsPassword)
        assertEquals(profile.portHopping, reparsed.portHopping)
        assertEquals(profile.pinnedCA256, reparsed.pinnedCA256)
    }

    @Test
    fun outboundTemplate_mapsHysteria2ToXrayHysteriaProtocol() {
        val outbound = CoreOutboundBuilder.createInitOutbound(EConfigType.HYSTERIA2)

        assertEquals("hysteria", outbound?.protocol)
        assertTrue(outbound?.streamSettings != null)
    }
}
