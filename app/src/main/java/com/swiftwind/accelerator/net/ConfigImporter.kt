package com.swiftwind.accelerator.net

import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import java.io.BufferedReader
import java.io.StringReader

object ConfigImporter {

    fun split(raw: String): List<String> {
        var text = raw.trim()
        if (text.isEmpty()) return emptyList()

        if (!text.contains("[Interface]", ignoreCase = true)) {
            text = decodeBase64(text) ?: return emptyList()
        }

        return text
            .split(Regex("(?=\\[Interface\\])", RegexOption.IGNORE_CASE))
            .map { it.trim().replace("\r\n", "\n") }
            .filter { it.contains("[Interface]", true) && it.contains("[Peer]", true) }
            .distinct()
    }

    @Throws(BadConfigException::class, java.io.IOException::class)
    fun parse(configText: String): Config =
        Config.parse(BufferedReader(StringReader(configText)))

    fun endpointOf(configText: String): String {
        val match = Regex("(?im)^\\s*Endpoint\\s*=\\s*(.+?)\\s*$").find(configText)
        return match?.groupValues?.get(1)?.trim().orEmpty()
    }

    fun fingerprint(configText: String): String =
        configText.replace("\r\n", "\n").trim().hashCode().toString()

    private fun decodeBase64(text: String): String? {
        val compact = text.filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return null
        val candidates = listOf(
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
            android.util.Base64.DEFAULT
        )
        for (flags in candidates) {
            val decoded = runCatching {
                String(android.util.Base64.decode(compact, flags), Charsets.UTF_8)
            }.getOrNull()
            if (decoded != null && decoded.contains("[Interface]", ignoreCase = true)) {
                return decoded
            }
        }
        return null
    }
}
