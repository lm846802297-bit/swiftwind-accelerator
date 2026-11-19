package com.swiftwind.accelerator.vpn

import com.swiftwind.accelerator.data.Node
import com.swiftwind.accelerator.data.NodeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartNodePickerTest {

    private fun node(id: String, source: NodeSource = NodeSource.WARP): Node = Node(
        id = id,
        name = id,
        config = "[Interface]\nPrivateKey = x\nAddress = 172.16.0.2/32\n",
        endpoint = "127.0.0.1:51820",
        source = source
    )

    @Test
    fun `picks highest quality among healthy nodes`() {
        val nodes = listOf(node("slow"), node("fast"), node("failed"))
        val health = mapOf(
            "slow" to NodeHealth.OK,
            "fast" to NodeHealth.OK,
            "failed" to NodeHealth.FAIL
        )
        val quality = mapOf(
            "slow" to NodeQuality(score = 40),
            "fast" to NodeQuality(score = 88),
            "failed" to NodeQuality(score = 99)
        )

        assertEquals("fast", SmartNodePicker.pick(nodes, health, quality)?.id)
    }

    @Test
    fun `does not pick explicitly failed node even with quality score`() {
        val nodes = listOf(node("failed"), node("unknown"))
        val health = mapOf(
            "failed" to NodeHealth.FAIL,
            "unknown" to NodeHealth.UNKNOWN
        )
        val quality = mapOf(
            "failed" to NodeQuality(score = 95),
            "unknown" to NodeQuality(score = 50)
        )

        assertEquals("unknown", SmartNodePicker.pick(nodes, health, quality)?.id)
    }

    @Test
    fun `falls back to preferred node when no health or quality data`() {
        val nodes = listOf(node("first"), node("preferred"))
        assertEquals(
            "preferred",
            SmartNodePicker.pick(nodes, emptyMap(), emptyMap(), preferred = nodes[1])?.id
        )
    }

    @Test
    fun `returns null for empty list`() {
        assertNull(SmartNodePicker.pick(emptyList(), emptyMap(), emptyMap()))
    }
}
