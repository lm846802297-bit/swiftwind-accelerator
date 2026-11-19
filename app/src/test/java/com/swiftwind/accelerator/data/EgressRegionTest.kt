package com.swiftwind.accelerator.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EgressRegionTest {

    @Test
    fun parsesLocFromCloudflareTrace() {
        val trace = """
            fl=123f
            h=1.1.1.1
            ip=198.51.100.7
            ts=1700000000.000
            loc=US
            warp=on
        """.trimIndent()
        assertEquals("US", EgressRegion.parseTraceLoc(trace))
    }

    @Test
    fun ignoresMissingOrInvalidLoc() {
        assertEquals(null, EgressRegion.parseTraceLoc("ip=1.2.3.4\nwarp=on"))
        assertEquals(null, EgressRegion.parseTraceLoc("loc=USA"))
        assertEquals(null, EgressRegion.parseTraceLoc("loc=U1"))
    }

    @Test
    fun displaysKnownCountryInChinese() {
        assertEquals("美国", EgressRegion.displayName("us"))
        assertEquals("日本", EgressRegion.displayName("JP"))
    }

    @Test
    fun displaysChinaRegionsWithShortNames() {
        assertEquals("中国香港", EgressRegion.displayName("HK"))
        assertEquals("中国台湾", EgressRegion.displayName("TW"))
        assertEquals("中国澳门", EgressRegion.displayName("MO"))
        assertEquals("中国大陆", EgressRegion.displayName("CN"))
    }

    @Test
    fun regionLabelUsesChinaRegionName() {
        val node = Node(
            name = "WARP 优选2",
            config = "config",
            endpoint = "1.1.1.1:2408",
            source = NodeSource.WARP,
            regionCode = "HK"
        )
        assertEquals(
            "中国香港 · 最近检测出口",
            EgressRegion.label(node, regionFresh = true, hasCheckedAt = true)
        )
    }

    @Test
    fun warpWithoutDetectedCountryIsHonestAboutAnycast() {
        val node = Node(
            name = "WARP 优选1",
            config = "[Interface]\nPrivateKey = x\n[Peer]\nPublicKey = y\nEndpoint = 1.1.1.1:2408",
            endpoint = "1.1.1.1:2408",
            source = NodeSource.WARP
        )
        val label = EgressRegion.label(node, regionFresh = false, hasCheckedAt = false)
        assertTrue(label.contains("Anycast"))
    }

    @Test
    fun expiredCountryLabelAsksForRecheck() {
        val node = Node(
            name = "导入节点",
            config = "config",
            endpoint = "1.2.3.4:51820",
            source = NodeSource.IMPORT,
            regionCode = "US"
        )
        assertEquals(
            "出口地区待复检",
            EgressRegion.label(node, regionFresh = false, hasCheckedAt = true)
        )
    }
}
