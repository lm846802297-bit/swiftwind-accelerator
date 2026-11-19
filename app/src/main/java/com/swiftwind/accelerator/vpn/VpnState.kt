package com.swiftwind.accelerator.vpn

import kotlin.math.roundToInt

sealed interface VpnState {
    data object Idle : VpnState
    data object Connecting : VpnState
    data object Connected : VpnState
    data object Disconnecting : VpnState
    data class Error(val message: String) : VpnState
}

enum class NodeHealth { UNKNOWN, CHECKING, OK, FAIL }

/** 体检标签有效期：超过后视为未知，不能继续参与选线和握手超时判断。 */
const val NODE_HEALTH_TTL_MS = 10 * 60 * 1000L

fun effectiveHealth(
    health: NodeHealth?,
    checkedAt: Long?,
    now: Long = System.currentTimeMillis()
): NodeHealth {
    if (checkedAt == null) return NodeHealth.UNKNOWN
    if (now - checkedAt > NODE_HEALTH_TTL_MS) return NodeHealth.UNKNOWN
    return health ?: NodeHealth.UNKNOWN
}

/**
 * 一次真机体检的结果。
 * handshakeMs：从拉起隧道到检测到 WireGuard 握手的耗时；
 * httpLatencyMs：隧道内首个 HTTPS 响应头的耗时；
 * throughputKbps：隧道内 256KB 下载的实测吞吐（从首字节开始计时），单位 kbps。
 */
data class ProbeResult(
    val healthy: Boolean,
    val handshakeMs: Long = 0L,
    val httpLatencyMs: Long = 0L,
    val throughputKbps: Int = 0
) {
    val score: Int
        get() {
            if (!healthy) return 0
            val handshakeScore =
                (40.0 * (1.0 - handshakeMs.coerceIn(0L, 2500L) / 2500.0)).roundToInt()
            val httpScore =
                if (httpLatencyMs > 0L) {
                    (20.0 * (1.0 - httpLatencyMs.coerceIn(0L, 1500L) / 1500.0)).roundToInt()
                } else {
                    0
                }
            val speedScore =
                if (throughputKbps > 0) {
                    (40.0 * (throughputKbps.coerceAtMost(1500) / 1500.0)).roundToInt()
                } else {
                    0
                }
            return (handshakeScore + httpScore + speedScore).coerceIn(0, 100)
        }

    fun toQuality(): NodeQuality = NodeQuality(
        score = score,
        handshakeMs = handshakeMs,
        httpLatencyMs = httpLatencyMs,
        throughputKbps = throughputKbps
    )
}

data class NodeQuality(
    val score: Int = 0,
    val handshakeMs: Long = 0L,
    val httpLatencyMs: Long = 0L,
    val throughputKbps: Int = 0
) {
    val summary: String
        get() = buildList {
            if (handshakeMs > 0L) add("握手 ${handshakeMs}ms")
            if (throughputKbps > 0) add("下载 ${throughputKbps}kbps")
        }.joinToString(" · ").ifBlank { "质量分 $score" }
}

data class TunnelStats(
    val downloadBytes: Long = 0,
    val uploadBytes: Long = 0,
    val durationSeconds: Long = 0,
    val latestHandshakeMillis: Long = 0,
    val connected: Boolean = false
)
