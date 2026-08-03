package com.eggprice.calc

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 시세 네트워크 갱신: **한국 시간 기준 하루 1회**
 * 같은 날 재요청은 로컬 캐시만 사용 → 서버 부하 감소
 */
object DailyMarketGate {
    private const val PREF = "egg_daily_market"
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).apply {
        timeZone = TimeZone.getTimeZone("Asia/Seoul")
    }

    fun todayKey(): String = dayFmt.format(Date())

    fun refreshedToday(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return p.getString("fetch_day", null) == todayKey()
    }

    fun markRefreshed(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("fetch_day", todayKey())
            .putLong("fetched_at", System.currentTimeMillis())
            .apply()
    }

    fun nextHint(ctx: Context): String =
        if (refreshedToday(ctx)) "오늘은 이미 반영됨 · 내일 자동 갱신"
        else "오늘 시세를 1회 받을 수 있어요"
}
