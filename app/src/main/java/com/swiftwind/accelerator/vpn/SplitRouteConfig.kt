package com.swiftwind.accelerator.vpn

import android.content.Context
import android.util.Log
import com.swiftwind.accelerator.data.ConnectionMode

/**
 * 把 WireGuard 配置的 Peer.AllowedIPs 改写成对应连接方式的系统路由集合。
 *
 * - SMART：只把非中国 IPv4 放进隧道；中国 IPv4 不建 VPN 路由，走本地网络。
 *   IPv6 统一使用 2000::/3 走隧道，避免 IPv6 直接绕过代理。
 * - GLOBAL：所有 IPv4 / IPv6 都走隧道。
 *
 * GoBackend 会把 Peer.getAllowedIps() 逐条转成 VpnService.Builder.addRoute()，
 * 因此这里生成的就是 Android 真正使用的分流路由表，而不是 UI 层的伪开关。
 */
object SplitRouteConfig {
    private const val ASSET_FOREIGN_IPV4 = "foreign_ipv4.txt"
    private const val GLOBAL_ALLOWED_IPS = "0.0.0.0/0, ::/0"
    private const val GLOBAL_IPV6 = "2000::/3"
    private const val SMART_DNS_ROUTE = "1.1.1.1/32"
    private const val SMART_DNS = "223.5.5.5"

    fun forMode(context: Context, configText: String, mode: ConnectionMode): String {
        val allowedIps = when (mode) {
            ConnectionMode.GLOBAL -> GLOBAL_ALLOWED_IPS
            ConnectionMode.SMART -> smartAllowedIps(context)
        }
        var result = rewriteAllowedIps(configText, allowedIps)
        if (mode == ConnectionMode.SMART) {
            result = rewriteDns(result, SMART_DNS)
        }
        Log.d(
            "SwiftWindRoute",
            "mode=$mode allowedChars=${allowedIps.length} configChars=${result.length}"
        )
        return result
    }

    /**
     * 只替换 [Peer] 段里的 AllowedIPs。导入的配置可能包含 Interface 的 DNS、
     * 地址和 Peer 的 Endpoint；这些内容必须原样保留。
     */
    internal fun rewriteAllowedIps(configText: String, allowedIps: String): String {
        val usesCrLf = configText.contains("\r\n")
        val newline = if (usesCrLf) "\r\n" else "\n"
        val lines = configText.replace("\r\n", "\n").split("\n").toMutableList()

        var inPeer = false
        var replaced = false
        var firstPeerHeader = -1

        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("[") && trimmed.endsWith("]") -> {
                    inPeer = trimmed.equals("[Peer]", ignoreCase = true)
                    if (inPeer && firstPeerHeader < 0) firstPeerHeader = index
                }

                inPeer &&
                    trimmed.startsWith("AllowedIPs", ignoreCase = true) &&
                    trimmed.contains("=") -> {
                    lines[index] = "AllowedIPs = $allowedIps"
                    replaced = true
                }
            }
        }

        if (!replaced) {
            if (firstPeerHeader < 0) {
                throw IllegalArgumentException("WireGuard 配置缺少 [Peer] 段，无法应用分流规则")
            }
            lines.add(firstPeerHeader + 1, "AllowedIPs = $allowedIps")
        }

        val joined = lines.joinToString(newline)
        return if (configText.endsWith("\n")) joined + newline else joined
    }

    /**
     * 智能连接使用国内 DNS，避免 WARP 的海外 DNS 把国内站点优先解析到海外 CDN。
     * DNS 本身不加入 AllowedIPs，因此查询走本地网络。
     * 全局连接不改 DNS，继续使用配置里的 WARP DNS。
     */
    internal fun rewriteDns(configText: String, dns: String): String {
        val usesCrLf = configText.contains("\r\n")
        val newline = if (usesCrLf) "\r\n" else "\n"
        val lines = configText.replace("\r\n", "\n").split("\n").toMutableList()
        var inInterface = false
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("[") && trimmed.endsWith("]") -> {
                    inInterface = trimmed.equals("[Interface]", ignoreCase = true)
                }

                inInterface &&
                    trimmed.startsWith("DNS", ignoreCase = true) &&
                    trimmed.contains("=") -> {
                    lines[index] = "DNS = $dns"
                }
            }
        }
        val joined = lines.joinToString(newline)
        return if (configText.endsWith("\n")) joined + newline else joined
    }

    private fun smartAllowedIps(context: Context): String {
        val routes = context.assets.open(ASSET_FOREIGN_IPV4).bufferedReader().use { reader ->
            reader.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        }
        require(routes.isNotEmpty()) { "分流规则为空，无法建立智能连接" }
        val value = buildString {
            routes.forEachIndexed { index, route ->
                if (index > 0) append(", ")
                append(route)
            }
            append(", ")
            append(GLOBAL_IPV6)
            append(", ")
            append(SMART_DNS_ROUTE)
        }
        Log.d("SwiftWindRoute", "smart routes=${routes.size + 2} chars=${value.length}")
        return value
    }
}
