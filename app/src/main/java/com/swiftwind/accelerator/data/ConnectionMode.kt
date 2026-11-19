package com.swiftwind.accelerator.data

/**
 * 连接方式：
 * - SMART：连接前自动按体检健康度和质量分选线路；
 * - GLOBAL：使用用户当前选中的节点，全部应用流量都走 VPN。
 *
 * 两种方式共用同一个 WireGuard 隧道和全局路由；区别在选线策略，而不是额外的分流规则。
 */
enum class ConnectionMode {
    SMART,
    GLOBAL
}
