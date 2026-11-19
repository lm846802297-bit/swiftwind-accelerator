package com.swiftwind.accelerator.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class NodeHealthTest {

    private val now = 1_000_000L

    @Test
    fun `missing timestamp is treated as unknown`() {
        assertEquals(
            NodeHealth.UNKNOWN,
            effectiveHealth(NodeHealth.OK, null, now)
        )
    }

    @Test
    fun `fresh result keeps its health`() {
        assertEquals(
            NodeHealth.OK,
            effectiveHealth(NodeHealth.OK, now - 60_000L, now)
        )
        assertEquals(
            NodeHealth.FAIL,
            effectiveHealth(NodeHealth.FAIL, now - 60_000L, now)
        )
    }

    @Test
    fun `result expires after ttl`() {
        assertEquals(
            NodeHealth.UNKNOWN,
            effectiveHealth(
                NodeHealth.OK,
                now - NODE_HEALTH_TTL_MS - 1L,
                now
            )
        )
    }

    @Test
    fun `checking state is visible while probe is running`() {
        assertEquals(
            NodeHealth.CHECKING,
            effectiveHealth(NodeHealth.CHECKING, null, now)
        )
    }

    @Test
    fun `result remains valid exactly at ttl boundary`() {
        assertEquals(
            NodeHealth.OK,
            effectiveHealth(
                NodeHealth.OK,
                now - NODE_HEALTH_TTL_MS,
                now
            )
        )
    }
}
