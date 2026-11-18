package com.swiftwind.accelerator.net

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

class SubscriptionFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    fun fetch(url: String): String {
        val trimmed = url.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: throw IOException("订阅链接格式不正确")
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw IOException("只支持 HTTPS 订阅链接，HTTP 会明文传输节点私钥")
        }

        val request = Request.Builder()
            .url(trimmed)
            .header("User-Agent", "SwiftWind/1.0")
            .header("Accept", "text/plain, application/octet-stream, */*")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("订阅拉取失败：HTTP ${response.code}")
            // 重定向必须仍停留在 HTTPS，避免从安全链接跳回明文。
            if (!response.request.url.scheme.equals("https", ignoreCase = true)) {
                throw IOException("订阅链接被重定向到非 HTTPS 地址")
            }
            val body = response.body ?: throw IOException("订阅内容为空")
            if (body.contentLength() > MAX_BYTES) throw IOException("订阅内容过大")

            // contentLength 对 chunked 响应不可靠，按实际读取字节数强制截断。
            val bytes = body.byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (output.size().toLong() + read > MAX_BYTES) {
                        throw IOException("订阅内容过大")
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            return String(bytes, Charsets.UTF_8)
        }
    }

    private companion object {
        const val MAX_BYTES = 2L * 1024 * 1024
    }
}
