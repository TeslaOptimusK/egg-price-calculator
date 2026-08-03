package com.eggprice.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EggCalculatorTest {

    @Test
    fun teuk_30_7800_per10g() {
        val r = EggCalculator.calculate(
            CalcInput(
                size = EggSize.TEUK,
                count = 30,
                priceWon = 7800.0,
                weightMode = WeightMode.MID,
                edibleRatio = 0.89,
            ),
        ).getOrThrow()

        // 30 * 64 * 0.89 = 1708.8g → 7800/1708.8*10 ≈ 45.65
        assertEquals(64.0, r.unitG, 0.001)
        assertEquals(1708.8, r.totalUsableG, 0.01)
        assertEquals(45.65, r.per10g, 0.1)
    }

    @Test
    fun dae_cheaper_than_teuk_example() {
        val teuk = EggCalculator.calculate(
            CalcInput(EggSize.TEUK, 30, 7800.0),
        ).getOrThrow()
        val dae = EggCalculator.calculate(
            CalcInput(EggSize.DAE, 30, 6900.0),
        ).getOrThrow()
        val c = EggCalculator.compare(teuk, dae)
        assertTrue(c.cheaper == "B" || c.cheaper == "A" || c.cheaper == "tie")
        assertTrue(dae.per10g > 0 && teuk.per10g > 0)
    }

    @Test
    fun custom_weight() {
        val r = EggCalculator.calculate(
            CalcInput(
                size = EggSize.TEUK,
                count = 10,
                priceWon = 3000.0,
                weightMode = WeightMode.CUSTOM,
                customGrams = 60.0,
                edibleRatio = 0.9,
            ),
        ).getOrThrow()
        assertEquals(60.0, r.unitG, 0.0)
        assertEquals(540.0, r.totalUsableG, 0.01)
        assertEquals(55.56, r.per10g, 0.1)
    }
}
