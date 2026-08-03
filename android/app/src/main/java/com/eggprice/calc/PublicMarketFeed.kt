package com.eggprice.calc

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * 공개 시세 피드
 * 1) 오늘 원격 URL 성공 캐시 (SharedPreferences)
 * 2) assets/market-live.json
 *
 * 원격 URL: strings.xml market_feed_remote_url
 * (GitHub Actions가 갱신한 raw JSON — 서버 구축 불필요)
 */
object PublicMarketFeed {
    private const val PREF = "egg_public_feed"
    private const val KEY_JSON = "json"
    private const val KEY_DAY = "day"

    fun load(ctx: Context): MarketRef? {
        // 오늘 받은 원격 캐시 우선
        val pref = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val day = pref.getString(KEY_DAY, null)
        val cached = pref.getString(KEY_JSON, null)
        if (day == DailyMarketGate.todayKey() && !cached.isNullOrBlank()) {
            parse(cached)?.let { return it }
        }
        return loadAsset(ctx)
    }

    /**
     * 하루 1회 원격 시도. 메인 스레드에서 호출하지 말 것.
     * @return 성공 여부
     */
    fun refreshRemoteIfNeeded(ctx: Context): Boolean {
        if (DailyMarketGate.refreshedToday(ctx) &&
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY_DAY, null) == DailyMarketGate.todayKey()
        ) {
            return false
        }
        val url = ctx.getString(R.string.market_feed_remote_url).trim()
        if (url.isEmpty() || !url.startsWith("http")) {
            return false
        }
        return try {
            val text = httpGet(url)
            if (parse(text) == null) return false
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY_JSON, text)
                .putString(KEY_DAY, DailyMarketGate.todayKey())
                .apply()
            DailyMarketGate.markRefreshed(ctx)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun refreshRemoteAsync(ctx: Context, done: (Boolean) -> Unit) {
        thread {
            val ok = try {
                refreshRemoteIfNeeded(ctx.applicationContext)
            } catch (_: Exception) {
                false
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { done(ok) }
        }
    }

    private fun loadAsset(ctx: Context): MarketRef? {
        return try {
            val text = ctx.assets.open("market-live.json").bufferedReader().use { it.readText() }
            parse(text)
        } catch (_: Exception) {
            null
        }
    }

    private fun parse(text: String): MarketRef? {
        return try {
            val o = JSONObject(text)
            val pp = o.getJSONObject("packPrice")
            val pack = mutableMapOf<String, Double>()
            listOf("so", "jung", "dae", "teuk", "wang").forEach { id ->
                if (pp.has(id)) pack[id] = pp.getDouble(id)
            }
            if (pack.isEmpty()) null
            else MarketRef(
                asOf = o.optString("asOf", "앱 피드"),
                source = o.optString("source", "앱 공개 시세 피드 (키 불필요)"),
                packCount = o.optInt("packCount", 30),
                packPrice = pack,
                live = true,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 15000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return stream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
