package com.swiftwind.accelerator.net

import com.swiftwind.accelerator.vpn.ProbeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WarpEndpointsTest {

    @Test
    fun buildExpandsAnycastPoolAndKeepsRegisteredPorts() {
        val candidates = WarpEndpoints.build(
            ipv4Host = "162.159.192.5",
            ipv6Host = null,
            hostEndpoint = "engage.cloudflareclient.com:2408",
            ports = listOf(2408, 500, 1701, 4500)
        )

        assertEquals(15, candidates.size)
        assertEquals("188.114.97.1:2408", candidates.first().endpoint)
        assertTrue(candidates.any { it.endpoint == "188.114.97.1:500" })
        assertTrue(candidates.any { it.endpoint == "162.159.192.5:1701" })
        assertEquals(
            "同一 endpoint 不应重复",
            candidates.size,
            candidates.map { it.endpoint }.distinct().size
        )
    }

    @Test
    fun registeredEndpointAlreadyInAnycastPoolIsDeduplicated() {
        val candidates = WarpEndpoints.build(
            ipv4Host = "188.114.97.1",
            ipv6Host = null,
            hostEndpoint = "",
            ports = listOf(2408)
        )

        assertEquals(10, candidates.size)
        assertEquals(
            1,
            candidates.count { it.endpoint == "188.114.97.1:2408" }
        )
    }

    @Test
    fun qualityScoreFavorsLowerLatencyAndHigherThroughput() {
        val fast = ProbeResult(
            healthy = true,
            handshakeMs = 120,
            httpLatencyMs = 180,
            throughputKbps = 1200
        )
        val slow = ProbeResult(
            healthy = true,
            handshakeMs = 1800,
            httpLatencyMs = 1200,
            throughputKbps = 120
        )

        assertTrue("低延迟高吞吐的分数应更高", fast.score > slow.score)
        assertTrue("失败节点分数应为 0", ProbeResult(healthy = false).score == 0)
    }
}
