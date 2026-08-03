package com.eggprice.calc

/**
 * 전국 계란 참고 시세 (30개 팩) → 10g당 환산 후 의견
 * 웹 market.js 와 동일 로직
 */
data class MarketRef(
    val asOf: String = "2026-08-03",
    val source: String = "2026년 8월 소매 참고 추정치 (키 불필요)",
    val packCount: Int = 30,
    val packPrice: Map<String, Double> = DEFAULT_PACK,
    val live: Boolean = false,
) {
    companion object {
        val DEFAULT_PACK = mapOf(
            "so" to 5200.0,
            "jung" to 5900.0,
            "dae" to 6600.0,
            "teuk" to 7400.0,
            "wang" to 8200.0,
        )

        fun effective(ctx: android.content.Context): MarketRef {
            // 1) KAMIS 캐시 (선택·고급)
            val kamis = KamisClient.loadCachedMarket(ctx)
            // 2) 공개 피드 (assets/market-live.json) — 키 불필요
            val pub = PublicMarketFeed.load(ctx)
            var pack = DEFAULT_PACK.toMutableMap()
            var asOf = "2026-08-03"
            var source = "앱 기본 참고 시세 (키 불필요)"
            var live = false
            if (pub != null) {
                pack.putAll(pub.packPrice)
                asOf = pub.asOf
                source = pub.source
                live = true
            }
            if (kamis != null) {
                pack.putAll(kamis.packPrice)
                asOf = kamis.asOf
                source = kamis.source
                live = true
            }
            return MarketRef(
                asOf = asOf,
                source = source,
                packCount = 30,
                packPrice = pack,
                live = live,
            )
        }
    }
}

data class MarketOpinion(
    val level: String,
    val label: String,
    val tone: String, // cheap | fair | expensive
    val detail: String,
    val pctDiff: Double,
    val minePer10g: Double,
    val marketPer10g: Double,
    val packPrice: Double,
    val asOf: String,
    val source: String,
)

object MarketBench {
    fun marketPer10g(
        size: EggSize,
        excludeShell: Boolean,
        edibleRatio: Double,
        market: MarketRef = MarketRef(),
    ): Pair<Double, Double>? {
        val pack = market.packPrice[size.id] ?: return null
        val r = EggCalculator.calculate(
            CalcInput(
                size = size,
                count = market.packCount,
                priceWon = pack,
                weightMode = WeightMode.MID,
                excludeShell = excludeShell,
                edibleRatio = edibleRatio,
            ),
        ).getOrNull() ?: return null
        return r.per10g to pack
    }

    fun opinion(result: CalcResult, market: MarketRef = MarketRef()): MarketOpinion? {
        // 동일 사이즈(result.size) 시세만 사용
        val pair = marketPer10g(result.size, result.excludeShell, result.edibleRatio, market)
            ?: return null
        val ref = pair.first
        val pack = pair.second
        val mine = result.per10g
        val pct = ((mine - ref) / ref) * 100.0
        val abs = kotlin.math.abs(pct)

        val (level, label, tone) = when {
            pct <= -15 -> Triple("great", "매우 저렴", "cheap")
            pct <= -5 -> Triple("good", "저렴한 편", "cheap")
            pct < 5 -> Triple("fair", "시세 수준", "fair")
            pct < 15 -> Triple("high", "조금 비싼 편", "expensive")
            else -> Triple("pricey", "비싼 편", "expensive")
        }
        val dir = when {
            pct < 0 -> "저렴"
            pct > 0 -> "비쌈"
            else -> "동일"
        }
        val tag = if (market.live) "실시간" else "참고"
        val detail = if (pct == 0.0) {
            "${result.size.label} ${tag} 시세와 10g당이 같아요"
        } else {
            "${result.size.label} ${tag} 시세 대비 10g당 약 ${abs.toInt()}% ${dir}해요"
        }
        return MarketOpinion(
            level = level,
            label = label,
            tone = tone,
            detail = detail,
            pctDiff = pct,
            minePer10g = mine,
            marketPer10g = ref,
            packPrice = pack,
            asOf = market.asOf,
            source = market.source,
        )
    }

    fun sameSize(a: CalcResult, b: CalcResult): Boolean = a.size == b.size
}
