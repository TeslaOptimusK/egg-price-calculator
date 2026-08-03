package com.eggprice.calc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * KAMIS OpenAPI — 축산물(계란) 소매 시세
 * 인증키: SharedPreferences (기기 로컬)
 */
object KamisClient {
    private const val PREF = "egg_kamis"
    private const val CACHE_MS = 6L * 60 * 60 * 1000

    data class Creds(val certKey: String, val certId: String)

    data class LiveResult(
        val ok: Boolean,
        val asOf: String = "",
        val source: String = "",
        val packPrice: Map<String, Double> = emptyMap(),
        val error: String? = null,
        val fromCache: Boolean = false,
        val sampleCount: Int = 0,
    )

    fun loadCreds(ctx: Context): Creds {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return Creds(
            certKey = p.getString("cert_key", "") ?: "",
            certId = p.getString("cert_id", "") ?: "",
        )
    }

    fun saveCreds(ctx: Context, creds: Creds) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("cert_key", creds.certKey.trim())
            .putString("cert_id", creds.certId.trim())
            .apply()
    }

    fun loadCachedMarket(ctx: Context): MarketRef? {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val at = p.getLong("cache_at", 0L)
        if (at == 0L || System.currentTimeMillis() - at > CACHE_MS) return null
        val json = p.getString("cache_json", null) ?: return null
        return try {
            val o = JSONObject(json)
            val pack = mutableMapOf<String, Double>()
            val pp = o.getJSONObject("packPrice")
            pp.keys().forEach { k -> pack[k] = pp.getDouble(k) }
            if (pack.isEmpty()) null
            else MarketRef(
                asOf = o.optString("asOf"),
                source = o.optString("source", "KAMIS 실시간"),
                packCount = 30,
                packPrice = pack,
                live = true,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun saveCache(ctx: Context, ref: MarketRef, sampleCount: Int) {
        val pp = JSONObject()
        ref.packPrice.forEach { (k, v) -> pp.put(k, v) }
        val o = JSONObject()
            .put("asOf", ref.asOf)
            .put("source", ref.source)
            .put("packPrice", pp)
            .put("sampleCount", sampleCount)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("cache_json", o.toString())
            .putLong("cache_at", System.currentTimeMillis())
            .apply()
    }

    fun clearCache(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .remove("cache_json")
            .remove("cache_at")
            .apply()
    }

    fun fetchAsync(ctx: Context, force: Boolean, cb: (LiveResult) -> Unit) {
        thread {
            val result = try {
                fetchSync(ctx, force)
            } catch (e: Exception) {
                LiveResult(ok = false, error = e.message ?: "네트워크 오류")
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { cb(result) }
        }
    }

    private fun fetchSync(ctx: Context, force: Boolean): LiveResult {
        if (!force) {
            loadCachedMarket(ctx)?.let {
                return LiveResult(
                    ok = true,
                    asOf = it.asOf,
                    source = it.source,
                    packPrice = it.packPrice,
                    fromCache = true,
                )
            }
        }
        val creds = loadCreds(ctx)
        if (creds.certKey.isBlank() || creds.certId.isBlank()) {
            return LiveResult(ok = false, error = "KAMIS 인증키·아이디를 입력하세요")
        }

        val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
        val ymdCompact = ymd.replace("-", "")

        val urls = listOf(
            buildUrl(
                creds,
                "dailyPriceByCategoryList",
                mapOf(
                    "p_product_cls_code" to "01",
                    "p_item_category_code" to "500",
                    "p_regday" to ymdCompact,
                ),
            ),
            buildUrl(
                creds,
                "dailyPriceByCategoryList",
                mapOf(
                    "p_product_cls_code" to "01",
                    "p_item_category_code" to "500",
                ),
            ),
        )

        var lastErr = "시세를 가져오지 못함"
        val buckets = mutableMapOf<String, MutableList<Double>>()

        for (url in urls) {
            try {
                val text = httpGet(url)
                val items = extractItems(text)
                val norms = items.mapNotNull { normalize(it) }
                if (norms.isEmpty()) {
                    lastErr = "계란 사이즈 항목 없음"
                    continue
                }
                norms.forEach { (sizeId, pack30) ->
                    buckets.getOrPut(sizeId) { mutableListOf() }.add(pack30)
                }
                if (buckets.isNotEmpty()) break
            } catch (e: Exception) {
                lastErr = e.message ?: "오류"
            }
        }

        if (buckets.isEmpty()) return LiveResult(ok = false, error = lastErr)

        val pack = buckets.mapValues { (_, list) ->
            val s = list.sorted()
            s[s.size / 2]
        }
        val ref = MarketRef(
            asOf = ymd,
            source = "KAMIS 소매(축산물) 실시간",
            packCount = 30,
            packPrice = pack,
            live = true,
        )
        saveCache(ctx, ref, buckets.values.sumOf { it.size })
        return LiveResult(
            ok = true,
            asOf = ref.asOf,
            source = ref.source,
            packPrice = pack,
            sampleCount = buckets.values.sumOf { it.size },
        )
    }

    private fun buildUrl(creds: Creds, action: String, extra: Map<String, String>): String {
        val base = StringBuilder("https://www.kamis.or.kr/service/price/xml.do?")
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        base.append("action=").append(enc(action))
        base.append("&p_cert_key=").append(enc(creds.certKey))
        base.append("&p_cert_id=").append(enc(creds.certId))
        base.append("&p_returntype=json")
        extra.forEach { (k, v) -> base.append("&").append(enc(k)).append("=").append(enc(v)) }
        return base.toString()
    }

    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            requestMethod = "GET"
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return stream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun extractItems(text: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        try {
            val root = JSONObject(text)
            fun walk(node: Any?) {
                when (node) {
                    is JSONObject -> {
                        if (node.has("item_name") || node.has("itemName") || node.has("dpr1")) {
                            out.add(node)
                        }
                        node.keys().forEach { k -> walk(node.opt(k)) }
                    }
                    is JSONArray -> {
                        for (i in 0 until node.length()) walk(node.opt(i))
                    }
                }
            }
            walk(root)
        } catch (_: Exception) {
            // try array root
            try {
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out.add(o)
                }
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun matchSize(name: String): String? = when {
        name.contains("왕란") || name.contains("왕 란") -> "wang"
        name.contains("특란") || name.contains("특 란") -> "teuk"
        name.contains("대란") || name.contains("대 란") -> "dae"
        name.contains("중란") || name.contains("중 란") -> "jung"
        name.contains("소란") || name.contains("소 란") -> "so"
        else -> null
    }

    private fun parsePrice(v: Any?): Double? {
        if (v == null || v == JSONObject.NULL) return null
        val s = v.toString().replace(",", "").replace("원", "").trim()
        if (s.isEmpty() || s == "-" || s == "0") return null
        return s.toDoubleOrNull()?.takeIf { it > 0 }
    }

    private fun unitCount(unit: String, name: String): Int {
        val t = "$unit $name"
        return when {
            Regex("30\\s*개|1\\s*판|한\\s*판|30구").containsMatchIn(t) -> 30
            Regex("15\\s*개|15구").containsMatchIn(t) -> 15
            Regex("20\\s*개|20구").containsMatchIn(t) -> 20
            Regex("10\\s*개|10구").containsMatchIn(t) -> 10
            Regex("1\\s*개|개당").containsMatchIn(t) -> 1
            else -> 10
        }
    }

    private fun normalize(o: JSONObject): Pair<String, Double>? {
        val name = sequenceOf("item_name", "itemName", "kind_name", "kindName", "productName")
            .map { o.optString(it, "") }
            .firstOrNull { it.isNotBlank() } ?: return null
        val sizeId = matchSize(name) ?: return null
        val price = sequenceOf("dpr1", "dpr2", "price", "avg_price", "sale_price", "sprice")
            .map { parsePrice(o.opt(it)) }
            .firstOrNull { it != null } ?: return null
        val unit = o.optString("unit", "") + o.optString("unit_name", "")
        val cnt = unitCount(unit, name)
        val pack30 = price * 30.0 / cnt
        return sizeId to pack30
    }
}
