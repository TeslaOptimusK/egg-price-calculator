package com.eggprice.calc

/**
 * 특란 30구 지역·일자 시세 추세 (KAMIS/축평원 표 형태)
 */
data class TrendPoint(
    val date: String,
    val price: Double,
)

data class RegionTrend(
    val name: String,
    val latest: Double,
    val series: List<TrendPoint>,
) {
    fun changePct(): Double? {
        if (series.size < 2) return null
        val first = series.first().price
        val last = series.last().price
        if (first <= 0) return null
        return ((last - first) / first) * 100.0
    }
}

data class MarketTrend(
    val product: String = "특란 30구",
    val unit: String = "30개",
    val asOf: String = "",
    val source: String = "",
    val regions: Map<String, RegionTrend> = emptyMap(),
) {
    fun regionNames(): List<String> =
        (listOf("전국") + regions.keys.filter { it != "전국" }.sorted()).distinct()
            .filter { regions.containsKey(it) }

    fun get(name: String): RegionTrend? = regions[name] ?: regions["전국"]
}

/** 행정구역 → 앱 시세 지역 키 */
object RegionLocator {
    val DEFAULT = "전국"

    fun fromAddress(adminArea: String?, locality: String?, subAdmin: String?): String {
        val s = listOfNotNull(adminArea, subAdmin, locality).joinToString(" ")
        return when {
            s.contains("서울") -> "서울"
            s.contains("인천") -> "인천"
            s.contains("경기") -> "경기"
            s.contains("강원") -> "강원"
            s.contains("대전") -> "대전"
            s.contains("세종") -> "세종"
            s.contains("충남") || s.contains("충청남") -> "충남"
            s.contains("충북") || s.contains("충청북") -> "충북"
            s.contains("광주") -> "광주"
            s.contains("전남") || s.contains("전라남") -> "전남"
            s.contains("전북") || s.contains("전라북") || s.contains("전북특별") -> "전북"
            s.contains("대구") -> "대구"
            s.contains("경북") || s.contains("경상북") -> "경북"
            s.contains("부산") -> "부산"
            s.contains("울산") -> "울산"
            s.contains("경남") || s.contains("경상남") -> "경남"
            s.contains("제주") -> "제주"
            else -> DEFAULT
        }
    }
}
