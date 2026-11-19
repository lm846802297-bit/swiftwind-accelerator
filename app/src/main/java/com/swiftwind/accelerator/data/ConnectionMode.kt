package com.swiftwind.accelerator.data

/**
 * 连接方式（流量接管方式）：
 * - SMART：接管外网流量。中国 IPv4 目标直连，非中国 IPv4 与全球 IPv6 走 VPN；
 * - GLOBAL：接管全部流量。所有 IPv4 / IPv6 目标都走 VPN。
 *
 * 两种方式使用同一个当前选中节点；区别在系统 VPN 的路由集合，而不是选线策略。
 */
enum class ConnectionMode {
    SMART,
    GLOBAL
}
