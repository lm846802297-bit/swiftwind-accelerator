package com.swiftwind.accelerator.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigImporterTest {

    private val first = """
        [Interface]
        PrivateKey = private-key-one
        Address = 172.16.0.2/32
        DNS = 1.1.1.1

        [Peer]
        PublicKey = public-key-one
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = 188.114.97.1:2408
    """.trimIndent()

    private val second = """
        [Interface]
        PrivateKey = private-key-two
        Address = 172.16.0.3/32
        DNS = 1.0.0.1

        [Peer]
        PublicKey = public-key-two
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = 162.159.195.1:2408
    """.trimIndent()

    @Test
    fun splitsMultipleWireGuardConfigs() {
        val parts = ConfigImporter.split("$first\n\n$second")

        assertEquals("多节点订阅应拆成两份配置", 2, parts.size)
        assertTrue(parts[0].contains("private-key-one"))
        assertTrue(parts[1].contains("private-key-two"))
    }

    @Test
    fun splitIsCaseInsensitiveAndAcceptsPreamble() {
        val raw = "# subscription header\n" + first.lowercase().replace("[interface]", "[INTERFACE]")
            .replace("[peer]", "[Peer]") + "\n\n" + second

        val parts = ConfigImporter.split(raw)

        assertEquals(2, parts.size)
        assertTrue(parts[0].contains("private-key-one"))
        assertTrue(parts[1].contains("private-key-two"))
    }

    @Test
    fun identicalConfigsAreDeduplicated() {
        val parts = ConfigImporter.split("$first\n\n$first\n\n$second")

        assertEquals(2, parts.size)
    }

    @Test
    fun splitsUtf8BomConfigs() {
        val parts = ConfigImporter.split("\uFEFF$first\n\n\uFEFF$second")

        assertEquals("带 UTF-8 BOM 的订阅也应正常拆分", 2, parts.size)
        assertTrue(parts[0].contains("private-key-one"))
        assertTrue(parts[1].contains("private-key-two"))
    }

    @Test
    fun fingerprintNormalizesLineEndings() {
        val lf = first
        val crlf = first.replace("\n", "\r\n")

        assertEquals(ConfigImporter.fingerprint(lf), ConfigImporter.fingerprint(crlf))
    }
}
