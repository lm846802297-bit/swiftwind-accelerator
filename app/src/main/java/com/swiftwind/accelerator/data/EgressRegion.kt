package com.swiftwind.accelerator.data

import java.util.Locale

/**
 * 节点出口地区。
 *
 * WARP 入口是 Anycast，节点本身的 IP 不代表固定国家；这里只展示一次真实
 * 隧道探测中 Cloudflare trace 返回的出口国家/地区，并标明“最近检测出口”。
 */
object EgressRegion {

    data class EgressLocation(
        val countryCode: String? = null,
        val detail: String? = null
    )

    private val traceLocRegex = Regex("(?m)^loc=([A-Za-z]{2})\\s*$")
    private val traceIpRegex = Regex("(?m)^ip=(\\S+)\\s*$")

    fun parseTraceLoc(trace: String?): String? {
        if (trace.isNullOrBlank()) return null
        val code = traceLocRegex.find(trace)?.groupValues?.getOrNull(1) ?: return null
        return normalizeCode(code)
    }

    /** trace 里的出口 IP，用于进一步查询中国的省/市。 */
    fun parseTraceIp(trace: String?): String? {
        if (trace.isNullOrBlank()) return null
        return traceIpRegex.find(trace)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    /**
     * 解析 ipapi.co / ipwho.is 风格的 JSON，只对中国出口提取省市。
     * 请求失败或字段不全时返回 null，UI 只显示国家级地区。
     */
    fun parseGeoDetail(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val country = jsonString(body, "country_code")
        if (country != null && country.uppercase() != "CN") return null
        val region = jsonString(body, "region")
        val city = jsonString(body, "city")
        val regionCode = jsonString(body, "region_code")
        return localizeChinaDetail(region, city, regionCode)
    }

    fun localizeChinaDetail(region: String?, city: String?, regionCode: String?): String? {
        val province = region?.trim()?.takeIf { it.isNotBlank() }
            ?.let { chinaProvinces[it.lowercase()] }
            ?: regionCode?.trim()?.uppercase()?.let { chinaProvinceCodes[it] }
        val cityName = city?.trim()?.takeIf { it.isNotBlank() }
            ?.let { chinaCities[it.lowercase()] }
        return when {
            province != null && cityName != null && cityName != province -> "$province · $cityName"
            province != null -> province
            cityName != null -> cityName
            else -> null
        }
    }

    private fun jsonString(body: String, key: String): String? {
        val regex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"]*)\"")
        return regex.find(body)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    fun normalizeCode(raw: String?): String? {
        val code = raw?.trim()?.uppercase() ?: return null
        return code.takeIf { it.length == 2 && it.all { ch -> ch in 'A'..'Z' } }
    }

    fun displayName(code: String?): String? {
        val normalized = normalizeCode(code) ?: return null
        // 中国相关地区使用固定短名，避免系统 Locale 输出“中国香港特别行政区”等长名称。
        commonNames[normalized]?.let { return it }
        val chineseName = runCatching {
            Locale.Builder()
                .setRegion(normalized)
                .build()
                .getDisplayCountry(Locale.SIMPLIFIED_CHINESE)
        }.getOrNull()
        if (!chineseName.isNullOrBlank() && !chineseName.equals(normalized, ignoreCase = true)) {
            return chineseName
        }
        return normalized
    }

    /**
     * 节点行的地区文案。
     * regionFresh 为 false 时（体检结果过期），不要继续展示可能已经变化的出口国家。
     */
    fun label(node: Node, regionFresh: Boolean, hasCheckedAt: Boolean): String {
        if (!regionFresh && hasCheckedAt) return "出口地区待复检"
        val country = displayName(node.regionCode)
        if (country != null) {
            // 香港/台湾/澳门本身就是地区级名称，不再叠加城市。
            if (node.regionCode in setOf("HK", "TW", "MO")) {
                return "$country · 最近检测出口"
            }
            val detail = node.regionDetail?.trim()?.takeIf { it.isNotBlank() && it != country }
            if (detail != null) return "$country · $detail · 最近检测出口"
            return "$country · 最近检测出口"
        }
        return when (node.source) {
            NodeSource.WARP -> "全球 Anycast · 出口自动就近"
            NodeSource.SUBSCRIPTION -> "订阅线路 · 地区待体检"
            NodeSource.IMPORT -> "自定义线路 · 地区待体检"
        }
    }

    /** 列表/首页用的短文案，优先把地区本身完整显示出来。 */
    fun summary(node: Node, regionFresh: Boolean, hasCheckedAt: Boolean): String {
        if (!regionFresh && hasCheckedAt) return "出口地区待复检"
        val country = displayName(node.regionCode)
        if (country != null) {
            if (node.regionCode in setOf("HK", "TW", "MO")) return country
            val detail = node.regionDetail?.trim()?.takeIf { it.isNotBlank() && it != country }
            return if (detail != null) "$country · $detail" else country
        }
        return when (node.source) {
            NodeSource.WARP -> "全球 Anycast · 出口自动就近"
            NodeSource.SUBSCRIPTION -> "订阅线路 · 地区待体检"
            NodeSource.IMPORT -> "自定义线路 · 地区待体检"
        }
    }

    private val chinaProvinces = mapOf(
        "beijing" to "北京",
        "tianjin" to "天津",
        "hebei" to "河北",
        "shanxi" to "山西",
        "inner mongolia" to "内蒙古",
        "liaoning" to "辽宁",
        "jilin" to "吉林",
        "heilongjiang" to "黑龙江",
        "shanghai" to "上海",
        "jiangsu" to "江苏",
        "zhejiang" to "浙江",
        "anhui" to "安徽",
        "fujian" to "福建",
        "jiangxi" to "江西",
        "shandong" to "山东",
        "henan" to "河南",
        "hubei" to "湖北",
        "hunan" to "湖南",
        "guangdong" to "广东",
        "guangxi" to "广西",
        "hainan" to "海南",
        "chongqing" to "重庆",
        "sichuan" to "四川",
        "guizhou" to "贵州",
        "yunnan" to "云南",
        "tibet" to "西藏",
        "shaanxi" to "陕西",
        "gansu" to "甘肃",
        "qinghai" to "青海",
        "ningxia" to "宁夏",
        "xinjiang" to "新疆",
        "hong kong" to "中国香港",
        "macau" to "中国澳门",
        "macao" to "中国澳门",
        "taiwan" to "中国台湾"
    )

    private val chinaProvinceCodes = mapOf(
        "BJ" to "北京",
        "TJ" to "天津",
        "HE" to "河北",
        "SX" to "山西",
        "NM" to "内蒙古",
        "LN" to "辽宁",
        "JL" to "吉林",
        "HL" to "黑龙江",
        "SH" to "上海",
        "JS" to "江苏",
        "ZJ" to "浙江",
        "AH" to "安徽",
        "FJ" to "福建",
        "JX" to "江西",
        "SD" to "山东",
        "HA" to "河南",
        "HB" to "湖北",
        "HN" to "湖南",
        "GD" to "广东",
        "GX" to "广西",
        "HI" to "海南",
        "CQ" to "重庆",
        "SC" to "四川",
        "GZ" to "贵州",
        "YN" to "云南",
        "XZ" to "西藏",
        "SN" to "陕西",
        "GS" to "甘肃",
        "QH" to "青海",
        "NX" to "宁夏",
        "XJ" to "新疆"
    )

    private val chinaCities = mapOf(
        "beijing" to "北京",
        "shanghai" to "上海",
        "tianjin" to "天津",
        "chongqing" to "重庆",
        "guangzhou" to "广州",
        "shenzhen" to "深圳",
        "hangzhou" to "杭州",
        "nanjing" to "南京",
        "suzhou" to "苏州",
        "chengdu" to "成都",
        "wuhan" to "武汉",
        "xi'an" to "西安",
        "xian" to "西安",
        "changsha" to "长沙",
        "zhengzhou" to "郑州",
        "qingdao" to "青岛",
        "xiamen" to "厦门",
        "fuzhou" to "福州",
        "ningbo" to "宁波",
        "wuxi" to "无锡",
        "foshan" to "佛山",
        "dongguan" to "东莞",
        "zhuhai" to "珠海",
        "sanya" to "三亚",
        "haikou" to "海口",
        "kunming" to "昆明",
        "guiyang" to "贵阳",
        "nanning" to "南宁",
        "harbin" to "哈尔滨",
        "shenyang" to "沈阳",
        "dalian" to "大连",
        "jinan" to "济南",
        "hefei" to "合肥",
        "nanchang" to "南昌",
        "taiyuan" to "太原",
        "shijiazhuang" to "石家庄",
        "urumqi" to "乌鲁木齐",
        "lanzhou" to "兰州",
        "xining" to "西宁",
        "yinchuan" to "银川",
        "hohhot" to "呼和浩特",
        "lhasa" to "拉萨"
    )

    private val commonNames = mapOf(
        "US" to "美国",
        "JP" to "日本",
        "KR" to "韩国",
        "SG" to "新加坡",
        "HK" to "中国香港",
        "TW" to "中国台湾",
        "MO" to "中国澳门",
        "CN" to "中国大陆",
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
