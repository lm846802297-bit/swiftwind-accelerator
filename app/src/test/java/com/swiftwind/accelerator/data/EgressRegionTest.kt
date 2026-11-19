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
    fun parsesColoAndUsesEdgeLocationBeforeIpGeolocation() {
        val trace = """
            fl=123f
            ip=104.28.208.136
            loc=CN
            colo=LAX
            warp=on
        """.trimIndent()
        assertEquals("LAX", EgressRegion.parseTraceColo(trace))
        assertEquals("US", EgressRegion.edgeLocation(trace)?.countryCode)
        assertEquals("洛杉矶", EgressRegion.edgeLocation(trace)?.detail)
    }

    @Test
    fun mapsSanJoseEdgeInsteadOfAnycastIpCountry() {
        val trace = """
            fl=123f
            ip=104.28.208.136
            loc=CN
            colo=SJC
            warp=on
        """.trimIndent()
        assertEquals("SJC", EgressRegion.parseTraceColo(trace))
        assertEquals("US", EgressRegion.edgeLocation(trace)?.countryCode)
        assertEquals("圣何塞", EgressRegion.edgeLocation(trace)?.detail)
    }

    @Test
    fun ignoresInvalidColoAndFallsBackToLoc() {
        val trace = "loc=CN\ncolo=ZZZ\nwarp=on"
        assertEquals("ZZZ", EgressRegion.parseTraceColo(trace))
        assertEquals(null, EgressRegion.edgeLocation(trace))
        assertEquals("CN", EgressRegion.parseTraceLoc(trace))
    }

    @Test
    fun parsesEgressIpFromTrace() {
        val trace = """
            fl=123f
            ip=104.28.208.136
            ts=1700000000.000
            loc=CN
        """.trimIndent()
        assertEquals("104.28.208.136", EgressRegion.parseTraceIp(trace))
    }

    @Test
    fun localizesChinaProvinceAndCity() {
        assertEquals("上海", EgressRegion.localizeChinaDetail("Shanghai", "Shanghai", "SH"))
        assertEquals("广东 · 深圳", EgressRegion.localizeChinaDetail("Guangdong", "Shenzhen", "GD"))
        assertEquals(null, EgressRegion.localizeChinaDetail("Unknown", "Unknown", "ZZ"))
    }

    @Test
    fun parsesGeoJsonIntoChinaDetail() {
        val body = """
            {"country_code":"CN","region":"Shanghai","region_code":"SH","city":"Shanghai"}
        """.trimIndent()
        assertEquals("上海", EgressRegion.parseGeoDetail(body))
        assertEquals(null, EgressRegion.parseGeoDetail("{\"country_code\":\"US\"}"))
    }

    @Test
    fun labelAddsChinaCityDetail() {
        val node = Node(
            name = "WARP 优选4",
            config = "config",
            endpoint = "1.1.1.1:2408",
            source = NodeSource.WARP,
            regionCode = "CN",
            regionDetail = "上海"
        )
        assertEquals(
            "中国大陆 · 上海 · 最近检测出口",
            EgressRegion.label(node, regionFresh = true, hasCheckedAt = true)
        )
    }

    @Test
    fun summaryKeepsRegionVisibleWithoutSuffix() {
        val node = Node(
            name = "WARP 优选4",
            config = "config",
            endpoint = "1.1.1.1:2408",
            source = NodeSource.WARP,
            regionCode = "CN",
            regionDetail = "上海"
        )
        assertEquals(
            "中国大陆 · 上海",
            EgressRegion.summary(node, regionFresh = true, hasCheckedAt = true)
        )
        assertEquals(
            "中国香港",
            EgressRegion.summary(
                node.copy(regionCode = "HK", regionDetail = null),
                regionFresh = true,
                hasCheckedAt = true
            )
        )
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

    @Test
    fun regionWithoutThisSessionTimestampIsMarkedHistorical() {
        val node = Node(
            name = "WARP 优选3",
            config = "config",
            endpoint = "188.114.99.1:2408",
            source = NodeSource.WARP,
            regionCode = "CN",
            regionDetail = "上海"
        )
        // 没有本轮体检时间，只能称为“上次检测”，不能伪装成当前出口。
        assertEquals(
            "上次检测 · 上海",
            EgressRegion.summary(node, regionFresh = false, hasCheckedAt = false)
        )
        assertEquals(
            "上次检测出口 · 中国大陆 · 上海",
            EgressRegion.label(node, regionFresh = false, hasCheckedAt = false)
        )
    }
}
