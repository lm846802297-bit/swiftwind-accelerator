package com.swiftwind.accelerator.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class NodeSource {
    WARP,
    IMPORT,
    SUBSCRIPTION
}

data class Node(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val config: String,
    val endpoint: String,
    val source: NodeSource,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("config", config)
        put("endpoint", endpoint)
        put("source", source.name)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(json: JSONObject): Node = Node(
            id = json.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            name = json.optString("name").takeIf { it.isNotBlank() } ?: "未命名节点",
            config = json.getString("config"),
            endpoint = json.optString("endpoint", ""),
            source = runCatching { NodeSource.valueOf(json.optString("source", "IMPORT")) }
                .getOrDefault(NodeSource.IMPORT),
            createdAt = json.optLong("createdAt", System.currentTimeMillis())
        )

        fun listFromJson(raw: String?): List<Node> {
            if (raw.isNullOrBlank()) return emptyList()
            val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            return buildList {
                // 单条记录损坏时只跳过该条，不能让整份节点列表一起消失。
                for (i in 0 until array.length()) {
                    runCatching { add(fromJson(array.getJSONObject(i))) }
                }
            }.distinctBy { it.id }
        }
    }
}
