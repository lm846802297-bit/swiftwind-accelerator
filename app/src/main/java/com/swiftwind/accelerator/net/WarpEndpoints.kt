package com.swiftwind.accelerator.net

/**
 * 根据 WARP 注册接口返回的 v4 / v6 地址和端口列表生成线路。
 *
 * 关键点：不能写死 162.159.192.1 或只使用 engage.cloudflareclient.com。
 * 实测注册接口会分配具体的 ingress 地址（例如 162.159.192.5），
 * 只有这个地址在当前网络下能完成 WireGuard 握手。
 */
object WarpEndpoints {
    data class Candidate(val label: String, val endpoint: String)

    /**
     * 这些是 Cloudflare 的 WARP anycast 入口。不是第三方节点，也不需要服务器。
     * 8 个常用 anycast 主机覆盖 2408 主端口，另补 97.1 / 195.1 的 500 端口。
     * 入口会随时段、运营商和地区波动，所以这里只负责扩大候选范围，
     * 最终是否可用、哪条最优，以 App 内「一键体检」的真实握手和测速结果为准。
     */
    private val preferredAnycast = listOf(
        "188.114.97.1:2408",
        "162.159.195.1:2408",
        "188.114.99.1:2408",
        "188.114.98.1:500",
        "188.114.96.1:2408",
        "162.159.192.1:2408",
        "162.159.193.1:2408",
        "162.159.194.1:2408",
        "188.114.97.1:500",
        "162.159.195.1:500"
    )

    fun build(
        ipv4Host: String?,
        ipv6Host: String?,
        hostEndpoint: String,
        ports: List<Int>
    ): List<Candidate> {
        val defaultPort = ports.firstOrNull { it > 0 } ?: 2408
        val candidates = linkedMapOf<String, Candidate>()

        // 先放实测响应更稳定的 anycast 入口，注册返回的地址作为补充。
        preferredAnycast.forEachIndexed { index, endpoint ->
            candidates.putIfAbsent(
                endpoint,
                Candidate("优选${index + 1}", endpoint)
            )
        }

        if (!ipv4Host.isNullOrBlank()) {
            ports.filter { it > 0 }.distinct().forEach { port ->
                candidates.putIfAbsent(
                    "$ipv4Host:$port",
                    Candidate("注册$port", "$ipv4Host:$port")
                )
            }
        }

        if (!ipv6Host.isNullOrBlank()) {
            candidates.putIfAbsent(
                "$ipv6Host:$defaultPort",
                Candidate("IPv6", "$ipv6Host:$defaultPort")
            )
        }

        if (hostEndpoint.isNotBlank()) {
            candidates.putIfAbsent(
                hostEndpoint,
                Candidate("域名", hostEndpoint)
            )
        }

        return candidates.values.toList()
    }

    fun replaceEndpoint(configText: String, endpoint: String): String =
        configText.replace(
            Regex("""(?im)^\s*Endpoint\s*=\s*.*$"""),
            "Endpoint = $endpoint"
        )
}
