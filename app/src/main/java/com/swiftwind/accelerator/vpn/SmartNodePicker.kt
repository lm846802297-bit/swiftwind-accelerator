package com.swiftwind.accelerator.vpn

import com.swiftwind.accelerator.data.Node

/**
 * “智能连接”的选线规则：
 * 1. 体检明确可用的线路优先，按质量分从高到低；
 * 2. 没有明确可用线路时，选择有历史质量分且未被标记失效的线路；
 * 3. 都没有时才回退当前节点，避免把用户主动选中的线路无故改掉。
 *
 * 这里只做纯选择，不发起连接；实际连接仍由 AppViewModel 负责。
 */
object SmartNodePicker {
    fun pick(
        nodes: List<Node>,
        health: Map<String, NodeHealth>,
        quality: Map<String, NodeQuality>,
        preferred: Node? = null
    ): Node? {
        if (nodes.isEmpty()) return null

        val healthy = nodes.filter { health[it.id] == NodeHealth.OK }
        if (healthy.isNotEmpty()) {
            return healthy.maxWithOrNull(
                compareBy<Node> { quality[it.id]?.score ?: 0 }
                    .thenBy { if (it.id == preferred?.id) 1 else 0 }
            )
        }

        val scored = nodes.filter {
            health[it.id] != NodeHealth.FAIL &&
                (quality[it.id]?.score ?: 0) > 0
        }
        if (scored.isNotEmpty()) {
            return scored.maxWithOrNull(
                compareBy<Node> { quality[it.id]?.score ?: 0 }
                    .thenBy { if (it.id == preferred?.id) 1 else 0 }
            )
        }

        return preferred?.takeIf { current -> nodes.any { it.id == current.id } }
            ?: nodes.firstOrNull()
    }
}
