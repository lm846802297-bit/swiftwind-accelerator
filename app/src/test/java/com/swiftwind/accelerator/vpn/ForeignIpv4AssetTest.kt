package com.swiftwind.accelerator.vpn

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 分流资产回归保护。
 *
 * 智能连接会把 foreign_ipv4.txt 的 CIDR 直接交给 VpnService.addRoute()。
 * 压缩后的宽路由很容易覆盖 RFC1918、链路本地、回环、CGNAT 等网段；
 * 一旦覆盖，局域网设备会被错误送进 VPN。这里直接检查资产，不依赖
 * Android Context，保证重新生成 CIDR 表后也能在 JVM 单测里拦住回归。
 */
class ForeignIpv4AssetTest {

    private val excluded = listOf(
        "0.0.0.0/8",
        "10.0.0.0/8",
        "100.64.0.0/10",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.0.0.0/24",
        "192.168.0.0/16",
        "198.18.0.0/15",
        "224.0.0.0/4",
        "240.0.0.0/4",
        "255.255.255.255/32"
    ).map { Cidr.parse(it) }

    @Test
    fun assetDoesNotRoutePrivateOrReservedIpv4() {
        val routes = loadRoutes()
        assertTrue("分流资产不应为空", routes.isNotEmpty())
        routes.forEach { route ->
            excluded.forEach { blocked ->
                if (route.overlaps(blocked)) {
                    fail("分流资产 ${route.text} 覆盖了保留网段 ${blocked.text}")
                }
            }
        }
    }

    private fun loadRoutes(): List<Cidr> {
        val candidates = listOf(
            File("src/main/assets/foreign_ipv4.txt"),
            File("app/src/main/assets/foreign_ipv4.txt"),
            File("../app/src/main/assets/foreign_ipv4.txt")
        )
        val asset = candidates.firstOrNull { it.isFile }
            ?: error("找不到 foreign_ipv4.txt，工作目录=${System.getProperty("user.dir")}")
        return asset.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { Cidr.parse(it) }
    }

    private data class Cidr(
        val text: String,
        val start: Long,
        val end: Long
    ) {
        fun overlaps(other: Cidr): Boolean = start <= other.end && end >= other.start

        companion object {
            fun parse(value: String): Cidr {
                val parts = value.split("/")
                require(parts.size == 2) { "非法 CIDR: $value" }
                val prefix = parts[1].toInt()
                require(prefix in 0..32) { "非法前缀: $value" }
                val address = ipv4ToLong(parts[0])
                val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
                val start = address and mask
                val size = 1L shl (32 - prefix)
                return Cidr(value, start, start + size - 1)
            }

            private fun ipv4ToLong(value: String): Long {
                val octets = value.split(".")
                require(octets.size == 4) { "非法 IPv4: $value" }
                return octets.fold(0L) { acc, octet ->
                    val part = octet.toInt()
                    require(part in 0..255) { "非法 IPv4: $value" }
                    (acc shl 8) or part.toLong()
                }
            }
        }
    }
}
