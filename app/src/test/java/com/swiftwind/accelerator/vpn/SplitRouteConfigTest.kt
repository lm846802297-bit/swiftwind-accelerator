package com.swiftwind.accelerator.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitRouteConfigTest {
    private val config = """
        [Interface]
        PrivateKey = private-key
        Address = 172.16.0.2/32
        DNS = 1.1.1.1

        [Peer]
        PublicKey = public-key
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = engage.cloudflareclient.com:2408
    """.trimIndent()

    @Test
    fun replacesAllowedIpsOnlyInsidePeer() {
        val result = SplitRouteConfig.rewriteAllowedIps(config, "1.1.1.1/32, 2000::/3")
        val lines = result.lines()
        assertEquals("AllowedIPs = 1.1.1.1/32, 2000::/3", lines.first { it.startsWith("AllowedIPs") })
        assertFalse(result.contains("AllowedIPs = 0.0.0.0/0"))
        assertTrue(result.contains("DNS = 1.1.1.1"))
        assertTrue(result.contains("Endpoint = engage.cloudflareclient.com:2408"))
    }

    @Test
    fun insertsAllowedIpsWhenPeerHasNone() {
        val input = """
            [Interface]
            PrivateKey = private-key

            [Peer]
            PublicKey = public-key
            Endpoint = 162.159.192.1:2408
        """.trimIndent()
        val result = SplitRouteConfig.rewriteAllowedIps(input, "8.8.8.8/32")
        val peerIndex = result.lines().indexOf("[Peer]")
        assertEquals("AllowedIPs = 8.8.8.8/32", result.lines()[peerIndex + 1])
    }

    @Test
    fun preservesCrLfLineEndings() {
        val input = "[Interface]\r\nPrivateKey = a\r\n[Peer]\r\nPublicKey = b\r\nAllowedIPs = 0.0.0.0/0\r\n"
        val result = SplitRouteConfig.rewriteAllowedIps(input, "1.1.1.1/32")
        assertTrue(result.contains("\r\n"))
        assertFalse(result.contains("\n\n"))
    }

    @Test
    fun rewritesSmartDnsInsideInterface() {
        val result = SplitRouteConfig.rewriteDns(config, "223.5.5.5")
        assertTrue(result.contains("DNS = 223.5.5.5"))
        assertFalse(result.contains("DNS = 1.1.1.1"))
        assertTrue(result.contains("AllowedIPs = 0.0.0.0/0, ::/0"))
        assertTrue(result.contains("Endpoint = engage.cloudflareclient.com:2408"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsConfigWithoutPeer() {
        SplitRouteConfig.rewriteAllowedIps("[Interface]\nPrivateKey = a", "1.1.1.1/32")
    }
}
