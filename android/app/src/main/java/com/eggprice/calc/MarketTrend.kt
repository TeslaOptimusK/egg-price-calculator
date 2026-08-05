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

    /** 드롭다운·피드에 쓰는 권역 전체 (데이터 있으면 표시) */
    val ALL_KEYS = listOf(
        "전국",
        "서울", "인천", "경기",
        "강원",
        "대전", "세종", "충남", "충북",
        "광주", "전남", "전북",
        "대구", "경북",
        "부산", "울산", "경남",
        "제주",
    )

    /**
     * 위치 권역이 피드에 없을 때 가까운 권역 순서.
     * 예: 경남 데이터 없으면 부산 → 울산 → 대구 → 전국
     */
    private val NEAREST = mapOf(
        "경남" to listOf("부산", "울산", "대구", "경북", "전국"),
        "부산" to listOf("경남", "울산", "대구", "전국"),
        "울산" to listOf("경남", "부산", "대구", "전국"),
        "대구" to listOf("경북", "경남", "부산", "전국"),
        "경북" to listOf("대구", "강원", "전국"),
        "광주" to listOf("전남", "전북", "전국"),
        "전남" to listOf("광주", "전북", "경남", "전국"),
        "전북" to listOf("광주", "전남", "충남", "전국"),
        "세종" to listOf("대전", "충남", "충북", "전국"),
        "제주" to listOf("전남", "광주", "전국"),
        "서울" to listOf("경기", "인천", "전국"),
        "인천" to listOf("경기", "서울", "전국"),
        "경기" to listOf("서울", "인천", "전국"),
        "대전" to listOf("세종", "충남", "충북", "전국"),
        "충남" to listOf("대전", "세종", "충북", "전국"),
        "충북" to listOf("대전", "충남", "강원", "전국"),
        "강원" to listOf("경기", "충북", "경북", "전국"),
    )

    fun fromAddress(adminArea: String?, locality: String?, subAdmin: String?): String {
        val s = listOfNotNull(adminArea, subAdmin, locality).joinToString(" ")
        // 시·군 별칭 먼저 (Geocoder가 도명 없이 시만 줄 때)
        when {
            s.contains("창원") || s.contains("김해") || s.contains("진주") ||
                s.contains("양산") || s.contains("거제") || s.contains("통영") ||
                s.contains("사천") || s.contains("밀양") || s.contains("함안") -> return "경남"
            s.contains("수원") || s.contains("성남") || s.contains("용인") ||
                s.contains("고양") || s.contains("화성") || s.contains("부천") ||
                s.contains("안양") || s.contains("남양주") || s.contains("의정부") ||
                s.contains("평택") || s.contains("시흥") || s.contains("김포") ||
                s.contains("광명") || s.contains("하남") || s.contains("이천") ||
                s.contains("파주") || s.contains("광주") && s.contains("경기") -> return "경기"
            s.contains("청주") || s.contains("충주") || s.contains("제천") -> return "충북"
            s.contains("천안") || s.contains("아산") || s.contains("서산") ||
                s.contains("당진") || s.contains("논산") || s.contains("보령") -> return "충남"
            s.contains("전주") || s.contains("익산") || s.contains("군산") ||
                s.contains("완주") -> return "전북"
            s.contains("목포") || s.contains("여수") || s.contains("순천") ||
                s.contains("광양") || s.contains("나주") -> return "전남"
            s.contains("포항") || s.contains("구미") || s.contains("경주") ||
                s.contains("안동") || s.contains("김천") -> return "경북"
            s.contains("춘천") || s.contains("강릉") || s.contains("원주") ||
                s.contains("속초") || s.contains("동해") -> return "강원"
        }
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

    /**
     * 감지 권역이 피드에 있으면 그대로, 없으면 가까운 권역 → 전국.
     * @return Pair(적용 권역, 감지 권역) — 둘이 다르면 폴백된 것
     */
    fun pickAvailable(detected: String, available: Collection<String>): Pair<String, String> {
        val set = available.toSet()
        if (set.contains(detected)) return detected to detected
        for (alt in NEAREST[detected].orEmpty()) {
            if (set.contains(alt)) return alt to detected
        }
        return if (set.contains(DEFAULT)) DEFAULT to detected else (available.firstOrNull() ?: DEFAULT) to detected
    }
}
