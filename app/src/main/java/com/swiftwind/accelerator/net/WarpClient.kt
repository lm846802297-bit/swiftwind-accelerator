package com.swiftwind.accelerator.net

import com.swiftwind.accelerator.data.Node
import com.swiftwind.accelerator.data.NodeSource
import com.wireguard.crypto.KeyPair
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class WarpClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    private data class WarpRegistration(
        val node: Node,
        val endpoints: List<WarpEndpoints.Candidate>
    )

    fun register(): Node = registerRegistration().node

    private fun registerRegistration(): WarpRegistration {
        val keyPair = KeyPair()
        val publicKey = keyPair.publicKey.toBase64()

        val tos = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val body = JSONObject().apply {
            put("key", publicKey)
            put("install_id", "")
            put("fcm_token", "")
            put("tos", tos)
            put("model", "Android")
            put("type", "Android")
            put("locale", "en_US")
            put("serial_number", "")
            put("os_version", "16")
        }

        val request = Request.Builder()
            .url("https://api.cloudflareclient.com/v0a5641/reg")
            .header("CF-Client-Version", "a-6.38.9-5641")
            .header("User-Agent", "1.1.1.1/6.38.9-5641 (Android 16.0.0)")
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("WARP 注册失败：HTTP ${resp.code}")
            }
            val text = resp.body?.string() ?: throw IOException("WARP 返回为空")
            val json = JSONObject(text)
            val config = json.getJSONObject("config")
            val peer = config.getJSONArray("peers").getJSONObject(0)
            val endpointJson = peer.getJSONObject("endpoint")
            val hostEndpoint = endpointJson.optString("host", "")
            val rawV4 = endpointJson.optString("v4", "")
            val rawV6 = endpointJson.optString("v6", "")
            val ports = endpointJson.optJSONArray("ports")?.let { array ->
                (0 until array.length()).map { array.getInt(it) }
            } ?: listOf(2408)

            val ipv4Host = hostOf(rawV4)
            val ipv6Host = hostOf(rawV6)
            val candidates = WarpEndpoints.build(ipv4Host, ipv6Host, hostEndpoint, ports)
            val primaryEndpoint = candidates.firstOrNull()?.endpoint ?: hostEndpoint

            val addresses = config.getJSONObject("interface").getJSONObject("addresses")
            val v4 = addresses.optString("v4", "")
            val v6 = addresses.optString("v6", "")
            val peerPublicKey = peer.getString("public_key")

            val interfaceAddress = buildList {
                if (v4.isNotBlank()) add("$v4/32")
                if (v6.isNotBlank()) add("$v6/128")
            }.joinToString(", ")

            val configText = buildString {
                appendLine("[Interface]")
                appendLine("PrivateKey = ${keyPair.privateKey.toBase64()}")
                appendLine("Address = $interfaceAddress")
                appendLine("DNS = 1.1.1.1, 1.0.0.1")
                appendLine("MTU = 1280")
                appendLine()
                appendLine("[Peer]")
                appendLine("PublicKey = $peerPublicKey")
                appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
                appendLine("Endpoint = $primaryEndpoint")
            }.trim()

            ConfigImporter.parse(configText)

            return WarpRegistration(
                node = Node(
                    name = "Cloudflare WARP",
                    config = configText,
                    endpoint = primaryEndpoint,
                    source = NodeSource.WARP
                ),
                endpoints = candidates.ifEmpty {
                    listOf(WarpEndpoints.Candidate("默认", primaryEndpoint))
                }
            )
        }
    }

    /**
     * 只注册一次 WARP 账号，然后基于注册接口分配的 ingress 地址生成多条线路。
     * 这些节点共用同一套密钥，切换线路不会触发重新注册。
     */
    fun registerNodes(): List<Node> {
        val registration = registerRegistration()
        return registration.endpoints.map { candidate ->
            val config = WarpEndpoints.replaceEndpoint(registration.node.config, candidate.endpoint)
            ConfigImporter.parse(config)
            Node(
                name = "WARP ${candidate.label}",
                config = config,
                endpoint = candidate.endpoint,
                source = NodeSource.WARP
            )
        }
    }

    /** 从 "162.159.192.5:0" 或 "[2606:...]:0" 中取出纯主机部分。 */
    private fun hostOf(raw: String): String? {
        if (raw.isBlank()) return null
        return if (raw.startsWith("[")) {
            val end = raw.indexOf(']')
            if (end <= 0) null else raw.substring(0, end + 1)
        } else {
            val colon = raw.indexOf(':')
            if (colon >= 0) raw.substring(0, colon) else raw
        }
    }
}
