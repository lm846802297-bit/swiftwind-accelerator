package com.swiftwind.accelerator.data

import java.util.Locale

/**
 * 节点出口地区。
 *
 * WARP 入口是 Anycast，节点本身的 IP 不代表固定国家。Cloudflare trace 的
 * `colo` 是实际处理请求的边缘机房（如 LAX），比 anycast IP 的 IP 库归属更可靠；
 * 因此优先用 colo 显示节点地区，只有无法映射时才退回 `loc` / ipapi 查询。
 */
object EgressRegion {

    data class EgressLocation(
        val countryCode: String? = null,
        val detail: String? = null
    )

    private val traceLocRegex = Regex("(?m)^loc=([A-Za-z]{2})\\s*$")
    private val traceIpRegex = Regex("(?m)^ip=(\\S+)\\s*$")
    private val traceColoRegex = Regex("(?m)^colo=([A-Za-z]{3})\\s*$")

    fun parseTraceLoc(trace: String?): String? {
        if (trace.isNullOrBlank()) return null
        val code = traceLocRegex.find(trace)?.groupValues?.getOrNull(1) ?: return null
        return normalizeCode(code)
    }

    /** trace 里的 Cloudflare 边缘机房 IATA 代码，例如 LAX / HKG / NRT。 */
    fun parseTraceColo(trace: String?): String? {
        if (trace.isNullOrBlank()) return null
        return traceColoRegex.find(trace)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.length == 3 && it.all { ch -> ch in 'A'..'Z' } }
    }

    /**
     * 已知 Cloudflare 边缘机房到国家/城市的映射。
     * colo 是实际服务请求的边缘机房，不等同于出口 IP 的注册归属。
     */
    fun edgeLocation(trace: String?): EgressLocation? {
        val colo = parseTraceColo(trace) ?: return null
        return edgeLocations[colo]
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
            // 有地区但没有本轮体检时间，说明是上一次会话/上一次连接的历史结果，
            // 不能写成“最近检测出口”，否则用户会误以为这是当前出口。
            if (!hasCheckedAt) {
                val detail = node.regionDetail?.trim()?.takeIf { it.isNotBlank() && it != country }
                return if (detail != null) {
                    "上次检测出口 · $country · $detail"
                } else {
                    "上次检测出口 · $country"
                }
            }
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
            // 没有本轮时间戳时只能称为“上次检测”，不能冒充当前出口。
            if (!hasCheckedAt) {
                val detail = node.regionDetail?.trim()?.takeIf { it.isNotBlank() && it != country }
                return if (detail != null) "上次检测 · $detail" else "上次检测 · $country"
            }
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

    private val edgeLocations = mapOf(
        "LAX" to EgressLocation("US", "洛杉矶"),
        "SFO" to EgressLocation("US", "旧金山"),
        "SEA" to EgressLocation("US", "西雅图"),
        "PDX" to EgressLocation("US", "波特兰"),
        "DEN" to EgressLocation("US", "丹佛"),
        "DFW" to EgressLocation("US", "达拉斯"),
        "ORD" to EgressLocation("US", "芝加哥"),
        "ATL" to EgressLocation("US", "亚特兰大"),
        "MIA" to EgressLocation("US", "迈阿密"),
        "IAD" to EgressLocation("US", "华盛顿"),
        "EWR" to EgressLocation("US", "纽瓦克"),
        "BOS" to EgressLocation("US", "波士顿"),
        "PHX" to EgressLocation("US", "凤凰城"),
        "LAS" to EgressLocation("US", "拉斯维加斯"),
        "YYZ" to EgressLocation("CA", "多伦多"),
        "YVR" to EgressLocation("CA", "温哥华"),
        "HKG" to EgressLocation("HK", "香港"),
        "TPE" to EgressLocation("TW", "台北"),
        "NRT" to EgressLocation("JP", "东京"),
        "KIX" to EgressLocation("JP", "大阪"),
        "ICN" to EgressLocation("KR", "首尔"),
        "SIN" to EgressLocation("SG", "新加坡"),
        "BKK" to EgressLocation("TH", "曼谷"),
        "KUL" to EgressLocation("MY", "吉隆坡"),
        "SYD" to EgressLocation("AU", "悉尼"),
        "MEL" to EgressLocation("AU", "墨尔本"),
        "AKL" to EgressLocation("NZ", "奥克兰"),
        "LHR" to EgressLocation("GB", "伦敦"),
        "MAN" to EgressLocation("GB", "曼彻斯特"),
        "CDG" to EgressLocation("FR", "巴黎"),
        "FRA" to EgressLocation("DE", "法兰克福"),
        "AMS" to EgressLocation("NL", "阿姆斯特丹"),
        "MAD" to EgressLocation("ES", "马德里"),
        "FCO" to EgressLocation("IT", "罗马"),
        "WAW" to EgressLocation("PL", "华沙"),
        "IST" to EgressLocation("TR", "伊斯坦布尔"),
        "DXB" to EgressLocation("AE", "迪拜"),
        "BOM" to EgressLocation("IN", "孟买"),
        "DEL" to EgressLocation("IN", "德里"),
        "GRU" to EgressLocation("BR", "圣保罗"),
        "EZE" to EgressLocation("AR", "布宜诺斯艾利斯"),
        "JNB" to EgressLocation("ZA", "约翰内斯堡")
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
