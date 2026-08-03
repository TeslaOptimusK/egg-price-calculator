package com.eggprice.calc

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

class MapActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var sameSize: CheckBox
    private lateinit var store: PriceHistoryStore
    private var focusSizeId: String = "teuk"
    private var pageReady = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "저장 지점 지도"

        store = PriceHistoryStore(this)
        focusSizeId = intent.getStringExtra(EXTRA_SIZE_ID) ?: "teuk"

        webView = findViewById(R.id.webMap)
        status = findViewById(R.id.textMapStatus)
        sameSize = findViewById(R.id.chkMapSameSize)
        sameSize.isChecked = true
        sameSize.setOnCheckedChangeListener { _, _ -> pushMarkers() }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                pushMarkers()
            }
        }
        webView.loadUrl("file:///android_asset/history_map.html")
    }

    private fun pushMarkers() {
        if (!pageReady) return
        var list = store.loadAll().filter { it.lat != null && it.lng != null }
        if (sameSize.isChecked) {
            list = list.filter { it.sizeId == focusSizeId }
        }
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject()
                    .put("lat", e.lat)
                    .put("lng", e.lng)
                    .put("sizeId", e.sizeId)
                    .put("sizeLabel", e.sizeLabel)
                    .put("count", e.count)
                    .put("per10g", e.per10g)
                    .put("note", e.note)
                    .put("locationLabel", e.locationLabel),
            )
        }
        val json = JSONObject.quote(arr.toString())
        webView.evaluateJavascript("setMarkers($json)", null)
        status.text = if (list.isEmpty()) {
            "위치가 있는 저장이 없어요"
        } else {
            "지도에 ${list.size}개 지점 · ${if (sameSize.isChecked) EggSize.fromId(focusSizeId).label + "만" else "전체"}"
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_SIZE_ID = "size_id"
    }
}
