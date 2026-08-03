package com.eggprice.calc

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

data class PriceHistoryEntry(
    val id: String,
    val savedAt: Long,
    val lat: Double?,
    val lng: Double?,
    val locationLabel: String,
    val note: String,
    val sizeId: String,
    val sizeLabel: String,
    val count: Int,
    val priceWon: Double,
    val weightMode: String,
    val customG: Double?,
    val excludeShell: Boolean,
    val edibleRatio: Double,
    val unitG: Double,
    val per10g: Double,
    val perEgg: Double,
)

class PriceHistoryStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("egg_price_history", Context.MODE_PRIVATE)
    private val key = "items"
    private val max = 50

    fun loadAll(): List<PriceHistoryEntry> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    add(fromJson(arr.getJSONObject(i)))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(entry: PriceHistoryEntry) {
        val list = loadAll().toMutableList()
        list.add(0, entry)
        while (list.size > max) list.removeAt(list.lastIndex)
        persist(list)
    }

    fun remove(id: String) {
        persist(loadAll().filter { it.id != id })
    }

    fun clear() {
        prefs.edit().remove(key).apply()
    }

    fun get(id: String): PriceHistoryEntry? = loadAll().find { it.id == id }

    private fun persist(list: List<PriceHistoryEntry>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    private fun toJson(e: PriceHistoryEntry) = JSONObject().apply {
        put("id", e.id)
        put("savedAt", e.savedAt)
        put("lat", e.lat ?: JSONObject.NULL)
        put("lng", e.lng ?: JSONObject.NULL)
        put("locationLabel", e.locationLabel)
        put("note", e.note)
        put("sizeId", e.sizeId)
        put("sizeLabel", e.sizeLabel)
        put("count", e.count)
        put("priceWon", e.priceWon)
        put("weightMode", e.weightMode)
        put("customG", e.customG ?: JSONObject.NULL)
        put("excludeShell", e.excludeShell)
        put("edibleRatio", e.edibleRatio)
        put("unitG", e.unitG)
        put("per10g", e.per10g)
        put("perEgg", e.perEgg)
    }

    private fun fromJson(o: JSONObject) = PriceHistoryEntry(
        id = o.getString("id"),
        savedAt = o.getLong("savedAt"),
        lat = if (o.isNull("lat")) null else o.getDouble("lat"),
        lng = if (o.isNull("lng")) null else o.getDouble("lng"),
        locationLabel = o.optString("locationLabel", "위치 없음"),
        note = o.optString("note", ""),
        sizeId = o.getString("sizeId"),
        sizeLabel = o.getString("sizeLabel"),
        count = o.getInt("count"),
        priceWon = o.getDouble("priceWon"),
        weightMode = o.optString("weightMode", "mid"),
        customG = if (o.isNull("customG")) null else o.getDouble("customG"),
        excludeShell = o.optBoolean("excludeShell", true),
        edibleRatio = o.optDouble("edibleRatio", DEFAULT_EDIBLE_RATIO),
        unitG = o.getDouble("unitG"),
        per10g = o.getDouble("per10g"),
        perEgg = o.getDouble("perEgg"),
    )

    companion object {
        fun newId(): String = "h_" + UUID.randomUUID().toString().take(8)

        /** 마지막 알려진 위치 (권한 있을 때) */
        @Suppress("MissingPermission")
        fun lastKnownLocation(context: Context): Location? {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(
                LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER,
            )
            var best: Location? = null
            for (p in providers) {
                try {
                    if (!lm.isProviderEnabled(p)) continue
                    val loc = lm.getLastKnownLocation(p) ?: continue
                    if (best == null || loc.accuracy < best.accuracy) best = loc
                } catch (_: Exception) {
                }
            }
            return best
        }

        fun reverseLabel(context: Context, lat: Double, lng: Double): String {
            return try {
                if (!Geocoder.isPresent()) return "위치 확인됨"
                @Suppress("DEPRECATION")
                val list = Geocoder(context, Locale.KOREA).getFromLocation(lat, lng, 1)
                val a = list?.firstOrNull() ?: return "위치 확인됨"
                listOfNotNull(a.adminArea, a.locality ?: a.subLocality)
                    .distinct()
                    .take(2)
                    .joinToString(" ")
                    .ifBlank { "위치 확인됨" }
            } catch (_: Exception) {
                "위치 확인됨"
            }
        }
    }
}
