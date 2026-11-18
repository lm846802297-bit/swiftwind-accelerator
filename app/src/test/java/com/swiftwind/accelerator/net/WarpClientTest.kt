package com.swiftwind.accelerator.net

import com.swiftwind.accelerator.data.NodeSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WarpClientTest {

    @Test
    fun registerProducesMultipleParseableWireGuardConfigs() = runBlocking {
        val nodes = WarpClient().registerNodes()

        println("WARP routes: " + nodes.map { it.endpoint })
        assertTrue("应生成多条线路", nodes.size >= 6)
        assertEquals("线路 endpoint 不应重复", nodes.size, nodes.map { it.endpoint }.distinct().size)
        nodes.forEach { node ->
            assertEquals(NodeSource.WARP, node.source)
            assertTrue("endpoint 应包含端口", node.endpoint.contains(":"))
            assertTrue(node.config.startsWith("[Interface]"))
            assertTrue(node.config.contains("[Peer]"))
            assertTrue(node.config.contains("AllowedIPs = 0.0.0.0/0, ::/0"))
            assertTrue(node.config.contains("Endpoint = ${node.endpoint}"))
            assertEquals(1, ConfigImporter.parse(node.config).peers.size)
        }
    }
}
