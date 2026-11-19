package com.swiftwind.accelerator.data

import android.content.Context
import android.content.SharedPreferences
import com.swiftwind.accelerator.vpn.NODE_HEALTH_TTL_MS
import com.swiftwind.accelerator.vpn.NodeHealth
import org.json.JSONArray
import org.json.JSONObject

object NodeStore {
    private const val PREFS = "swiftwind_store"
    private const val KEY_NODES = "nodes"
    private const val KEY_SELECTED = "selected_id"
    private const val KEY_HEALTH = "health_records"

    data class StoredHealth(
        val health: Map<String, NodeHealth> = emptyMap(),
        val checkedAt: Map<String, Long> = emptyMap()
    )

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun loadNodes(): List<Node> =
        Node.listFromJson(prefs.getString(KEY_NODES, null))

    @Synchronized
    fun saveNodes(nodes: List<Node>) {
        val array = JSONArray()
        nodes.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_NODES, array.toString()).apply()
    }

    var selectedId: String?
        get() = prefs.getString(KEY_SELECTED, null)
        set(value) {
            prefs.edit().putString(KEY_SELECTED, value).apply()
        }

    /**
     * 体检结果只保留仍然有效的时间戳。进程重启后“上次体检”和可用/不可用标签
     * 不再凭空消失，也不会把超过 TTL 的旧标签重新显示出来。
     */
    @Synchronized
    fun loadHealth(now: Long = System.currentTimeMillis()): StoredHealth {
        val raw = prefs.getString(KEY_HEALTH, null)
        if (raw.isNullOrBlank()) return StoredHealth()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return StoredHealth()
        val health = LinkedHashMap<String, NodeHealth>()
        val checkedAt = LinkedHashMap<String, Long>()
        for (i in 0 until array.length()) {
            runCatching {
                val json = array.getJSONObject(i)
                val id = json.optString("id").trim()
                if (id.isBlank()) return@runCatching
                val at = json.optLong("checkedAt", 0L)
                if (at <= 0L || now - at > NODE_HEALTH_TTL_MS) return@runCatching
                val value = NodeHealth.valueOf(json.optString("health"))
                if (value == NodeHealth.CHECKING) return@runCatching
                health[id] = value
                checkedAt[id] = at
            }
        }
        return StoredHealth(health, checkedAt)
    }

    @Synchronized
    fun saveHealth(health: Map<String, NodeHealth>, checkedAt: Map<String, Long>) {
        val valid = JSONArray()
        health.forEach { (id, value) ->
            val at = checkedAt[id] ?: return@forEach
            // 进行中的临时状态和没有时间戳的条目不落盘，避免重启后卡在“检测中”。
            if (value == NodeHealth.CHECKING || at <= 0L) return@forEach
            valid.put(
                JSONObject()
                    .put("id", id)
                    .put("health", value.name)
                    .put("checkedAt", at)
            )
        }
        if (valid.length() == 0) {
            prefs.edit().remove(KEY_HEALTH).apply()
        } else {
            prefs.edit().putString(KEY_HEALTH, valid.toString()).apply()
        }
    }
}
