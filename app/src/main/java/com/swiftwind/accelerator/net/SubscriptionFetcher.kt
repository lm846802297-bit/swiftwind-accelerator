package com.swiftwind.accelerator.net

import okhttp3.OkHttpClient
import okhttp3.Request
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
        if (uri.scheme != "http" && uri.scheme != "https") {
            throw IOException("只支持 http/https 订阅链接")
        }

        val request = Request.Builder()
            .url(trimmed)
            .header("User-Agent", "SwiftWind/1.0")
            .header("Accept", "text/plain, application/octet-stream, */*")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("订阅拉取失败：HTTP ${response.code}")
            val body = response.body ?: throw IOException("订阅内容为空")
            if (body.contentLength() > MAX_BYTES) throw IOException("订阅内容过大")
            return body.string()
        }
    }

    private companion object {
        const val MAX_BYTES = 2L * 1024 * 1024
    }
}
