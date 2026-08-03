package com.eggprice.calc

/**
 * SPEC.md — 웹 calc.js 와 동일 스펙
 * 껍질 포함 / 제외 옵션
 */
enum class EggSize(
    val id: String,
    val label: String,
    val midG: Double,
    val minG: Double,
) {
    SO("so", "소란", 40.0, 36.0),
    JUNG("jung", "중란", 48.0, 44.0),
    DAE("dae", "대란", 56.0, 52.0),
    TEUK("teuk", "특란", 64.0, 60.0),
    WANG("wang", "왕란", 70.0, 68.0);

    companion object {
        fun fromId(id: String): EggSize =
            entries.find { it.id == id } ?: TEUK
    }
}

enum class WeightMode { MID, MIN, CUSTOM }

data class CalcInput(
    val size: EggSize,
    val count: Int,
    val priceWon: Double,
    val weightMode: WeightMode = WeightMode.MID,
    val customGrams: Double? = null,
    /** true = 알맹이만 (껍질 제외) */
    val excludeShell: Boolean = true,
    val edibleRatio: Double = DEFAULT_EDIBLE_RATIO,
)

data class CalcResult(
    val size: EggSize,
    val count: Int,
    val priceWon: Double,
    val unitG: Double,
    val excludeShell: Boolean,
    val edibleRatio: Double,
    val shellRatio: Double,
    val usablePerEggG: Double,
    val totalUsableG: Double,
    val perGram: Double,
    val per10g: Double,
    val perEgg: Double,
)

data class CompareResult(
    val a: CalcResult,
    val b: CalcResult,
    val cheaper: String,
    val diff10g: Double,
    val message: String,
)

const val DEFAULT_EDIBLE_RATIO = 0.89

object EggCalculator {
    fun resolveUnitGrams(
        size: EggSize,
        mode: WeightMode,
        customGrams: Double?,
    ): Double? {
        return when (mode) {
            WeightMode.CUSTOM -> {
                val g = customGrams ?: return null
                if (g <= 0) null else g
            }
            WeightMode.MIN -> size.minG
            WeightMode.MID -> size.midG
        }
    }

    fun calculate(input: CalcInput): Result<CalcResult> {
        if (input.count < 1) return Result.failure(IllegalArgumentException("개수를 확인하세요"))
        if (input.priceWon < 0 || input.priceWon.isNaN()) {
            return Result.failure(IllegalArgumentException("가격을 확인하세요"))
        }
        val ratio = if (input.excludeShell) input.edibleRatio else 1.0
        if (ratio <= 0.0 || ratio > 1.0) {
            return Result.failure(IllegalArgumentException("알맹이 비율을 확인하세요"))
        }
        val unitG = resolveUnitGrams(input.size, input.weightMode, input.customGrams)
            ?: return Result.failure(IllegalArgumentException("1개 중량을 입력하세요"))

        val usablePerEggG = unitG * ratio
        val totalUsableG = usablePerEggG * input.count
        if (totalUsableG <= 0) {
            return Result.failure(IllegalArgumentException("중량이 올바르지 않습니다"))
        }

        val perGram = input.priceWon / totalUsableG
        return Result.success(
            CalcResult(
                size = input.size,
                count = input.count,
                priceWon = input.priceWon,
                unitG = unitG,
                excludeShell = input.excludeShell,
                edibleRatio = ratio,
                shellRatio = if (input.excludeShell) 1.0 - ratio else 0.0,
                usablePerEggG = usablePerEggG,
                totalUsableG = totalUsableG,
                perGram = perGram,
                per10g = perGram * 10,
                perEgg = input.priceWon / input.count,
            ),
        )
    }

    fun compare(a: CalcResult, b: CalcResult): CompareResult {
        val diff = a.per10g - b.per10g
        val abs = kotlin.math.abs(diff)
        return when {
            abs < 0.05 -> CompareResult(a, b, "tie", abs, "10g당 단가가 거의 같습니다")
            diff > 0 -> CompareResult(
                a, b, "B", abs,
                "상품 B가 10g당 약 ${abs.toInt()}원 저렴",
            )
            else -> CompareResult(
                a, b, "A", abs,
                "상품 A가 10g당 약 ${abs.toInt()}원 저렴",
            )
        }
    }
}
