package com.swiftwind.accelerator.vpn

sealed interface VpnState {
    data object Idle : VpnState
    data object Connecting : VpnState
    data object Connected : VpnState
    data object Disconnecting : VpnState
    data class Error(val message: String) : VpnState
}

enum class NodeHealth { UNKNOWN, CHECKING, OK, FAIL }

data class TunnelStats(
    val downloadBytes: Long = 0,
    val uploadBytes: Long = 0,
    val durationSeconds: Long = 0,
    val latestHandshakeMillis: Long = 0,
    val connected: Boolean = false
)
