package com.swiftwind.accelerator.data

import java.util.Locale

/**
 * 节点出口地区。
 *
 * WARP 入口是 Anycast，节点本身的 IP 不代表固定国家；这里只展示一次真实
 * 隧道探测中 Cloudflare trace 返回的出口国家/地区，并标明“上次体检出口”。
 */
object EgressRegion {

    private val traceLocRegex = Regex("(?m)^loc=([A-Za-z]{2})\\s*$")

    fun parseTraceLoc(trace: String?): String? {
        if (trace.isNullOrBlank()) return null
        val code = traceLocRegex.find(trace)?.groupValues?.getOrNull(1) ?: return null
        return normalizeCode(code)
    }

    fun normalizeCode(raw: String?): String? {
        val code = raw?.trim()?.uppercase() ?: return null
        return code.takeIf { it.length == 2 && it.all { ch -> ch in 'A'..'Z' } }
    }

    fun displayName(code: String?): String? {
        val normalized = normalizeCode(code) ?: return null
        val chineseName = runCatching {
            Locale.Builder()
                .setRegion(normalized)
                .build()
                .getDisplayCountry(Locale.SIMPLIFIED_CHINESE)
        }.getOrNull()
        if (!chineseName.isNullOrBlank() && !chineseName.equals(normalized, ignoreCase = true)) {
            return chineseName
        }
        return commonNames[normalized] ?: normalized
    }

    /**
     * 节点行的地区文案。
     * regionFresh 为 false 时（体检结果过期），不要继续展示可能已经变化的出口国家。
     */
    fun label(node: Node, regionFresh: Boolean, hasCheckedAt: Boolean): String {
        if (!regionFresh && hasCheckedAt) return "出口地区待复检"
        val country = displayName(node.regionCode)
        if (country != null) return "$country · 最近检测出口"
        return when (node.source) {
            NodeSource.WARP -> "全球 Anycast · 出口自动就近"
            NodeSource.SUBSCRIPTION -> "订阅线路 · 地区待体检"
            NodeSource.IMPORT -> "自定义线路 · 地区待体检"
        }
    }

    private val commonNames = mapOf(
        "US" to "美国",
        "JP" to "日本",
        "KR" to "韩国",
        "SG" to "新加坡",
        "HK" to "中国香港",
        "TW" to "中国台湾",
        "GB" to "英国",
        "DE" to "德国",
        "FR" to "法国",
        "NL" to "荷兰",
        "CA" to "加拿大",
        "AU" to "澳大利亚",
        "IN" to "印度",
        "BR" to "巴西",
        "RU" to "俄罗斯",
        "TR" to "土耳其",
        "AE" to "阿联酋",
        "MY" to "马来西亚",
        "TH" to "泰国",
        "VN" to "越南",
        "PH" to "菲律宾",
        "ID" to "印度尼西亚",
        "IT" to "意大利",
        "ES" to "西班牙",
        "PL" to "波兰",
        "SE" to "瑞典",
        "CH" to "瑞士",
        "AT" to "奥地利",
        "BE" to "比利时",
        "DK" to "丹麦",
        "NO" to "挪威",
        "FI" to "芬兰",
        "IE" to "爱尔兰",
        "PT" to "葡萄牙",
        "CZ" to "捷克",
        "HU" to "匈牙利",
        "RO" to "罗马尼亚",
        "GR" to "希腊",
        "UA" to "乌克兰",
        "ZA" to "南非",
        "MX" to "墨西哥",
        "AR" to "阿根廷",
        "CL" to "智利",
        "CO" to "哥伦比亚",
        "PE" to "秘鲁"
    )
}
