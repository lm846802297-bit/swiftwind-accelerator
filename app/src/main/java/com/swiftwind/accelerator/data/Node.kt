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
    // 最近一次检测到的出口国家/地区（ISO 3166-1 alpha-2）。
    // WARP 是 Anycast，出口会随网络和调度变化，所以这里只作为“最近检测出口”，
    // 不把它当成节点永久归属。
    val regionCode: String? = null,
    // 中国出口的省/市等细化地区，例如“上海”“广东 · 深圳”。
    val regionDetail: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("config", config)
        put("endpoint", endpoint)
        put("source", source.name)
        put("regionCode", regionCode ?: JSONObject.NULL)
        put("regionDetail", regionDetail ?: JSONObject.NULL)
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
            regionCode = if (json.isNull("regionCode")) {
                null
            } else {
                json.optString("regionCode")
                    .trim()
                    .uppercase()
                    .takeIf { it.length == 2 }
            },
            regionDetail = if (json.isNull("regionDetail")) {
                null
            } else {
                json.optString("regionDetail").trim().takeIf { it.isNotBlank() }
            },
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
