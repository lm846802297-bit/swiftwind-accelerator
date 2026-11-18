package com.swiftwind.accelerator.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

object NodeStore {
    private const val PREFS = "swiftwind_store"
    private const val KEY_NODES = "nodes"
    private const val KEY_SELECTED = "selected_id"

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
}
