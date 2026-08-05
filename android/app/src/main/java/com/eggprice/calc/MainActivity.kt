package com.eggprice.calc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.location.Geocoder
import android.location.LocationManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.eggprice.calc.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var historyStore: PriceHistoryStore
    private val wonFmt = NumberFormat.getNumberInstance(Locale.KOREA)
    private val sizes = EggSize.entries
    private var edibleRatio: Double = DEFAULT_EDIBLE_RATIO
    private var excludeShell: Boolean = true
    /** 알맹이 비율 상세(설명·슬라이더) 펼침 여부 — 기본 접힘 */
    private var edibleRatioExpanded: Boolean = false
    /** KAMIS 키 입력 영역 — 기본 접힘 (일반 유저용) */
    private var kamisExpanded: Boolean = false
    private var lastResult: CalcResult? = null
    private var historyEntries: List<PriceHistoryEntry> = emptyList()
    /** 공유용 전체 이력 (사이즈 필터 없음) */
    private var shareHistoryEntries: List<PriceHistoryEntry> = emptyList()
    private var suppressHistorySelect = false
    private var suppressShareSelect = false
    private var sameSizeOnly: Boolean = true
    private var marketRef: MarketRef = MarketRef()
    private var selectedRegion: String = RegionLocator.DEFAULT
    private var suppressRegionSelect = false
    /** locationPermission 용도: save | region */
    private var locationPurpose: String = "save"

    private val dateFmt = SimpleDateFormat("M/d HH:mm", Locale.KOREA)
    private val regionPrefs by lazy { getSharedPreferences("egg_region_pref", MODE_PRIVATE) }

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val ok = granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (locationPurpose == "region") {
            if (ok) resolveRegionFromLocation()
            else Toast.makeText(this, "위치 권한이 없어 지역을 직접 골라 주세요", Toast.LENGTH_SHORT).show()
        } else {
            saveCurrent(withLocation = ok)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        historyStore = PriceHistoryStore(this)
        marketRef = MarketRef.effective(this)
        selectedRegion = regionPrefs.getString("region", RegionLocator.DEFAULT) ?: RegionLocator.DEFAULT

        setupSpinners()
        setupSizeEggChips()
        setupCountChips()
        setupTabs()
        setupShellMode()
        setupListeners()
        setupSaveAndHistory()
        setupKamisAndMap()
        setupTrendUi()
        refreshHistorySpinners()
        // 앱 시작: 원격 피드(GitHub raw) 하루 1회 시도 → 실패 시 assets
        if (!DailyMarketGate.refreshedToday(this)) {
            PublicMarketFeed.refreshRemoteAsync(this) {
                marketRef = MarketRef.effective(this)
                if (!DailyMarketGate.refreshedToday(this)) {
                    DailyMarketGate.markRefreshed(this)
                }
                updateLiveStatus()
                refreshTrendUi()
                recalc()
            }
        }
        marketRef = MarketRef.effective(this)
        updateLiveStatus()
        updateShellUi()
        refreshTrendUi()
        recalc()
    }

    private fun setupKamisAndMap() {
        val creds = KamisClient.loadCreds(this)
        binding.editKamisKey.setText(creds.certKey)
        binding.editKamisId.setText(creds.certId)
        updateKamisUi()
        binding.rowKamisHeader.setOnClickListener {
            kamisExpanded = !kamisExpanded
            updateKamisUi()
        }

        // 일반: 하루 1회만 원격/로컬 피드 (서버 부하 감소)
        binding.btnFetchLive.setOnClickListener {
            if (DailyMarketGate.refreshedToday(this)) {
                Toast.makeText(this, DailyMarketGate.nextHint(this), Toast.LENGTH_SHORT).show()
                updateLiveStatus()
                return@setOnClickListener
            }
            binding.btnFetchLive.isEnabled = false
            PublicMarketFeed.refreshRemoteAsync(this) { remoteOk ->
                marketRef = MarketRef.effective(this)
                if (!DailyMarketGate.refreshedToday(this)) {
                    DailyMarketGate.markRefreshed(this)
                }
                updateLiveStatus()
                recalc()
                Toast.makeText(
                    this,
                    if (remoteOk) "오늘 원격 시세 반영 (하루 1회)"
                    else "오늘 시세 반영 · 내장 피드 (하루 1회)",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        // 고급: KAMIS 키가 있을 때만
        binding.btnSaveKamis.setOnClickListener {
            val key = binding.editKamisKey.text?.toString().orEmpty()
            val id = binding.editKamisId.text?.toString().orEmpty()
            if (key.isBlank() || id.isBlank()) {
                Toast.makeText(this, "일반 사용은 키 없이 가능해요. KAMIS는 선택 사항입니다.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            KamisClient.saveCreds(this, KamisClient.Creds(certKey = key, certId = id))
            binding.textLiveStatus.text = "KAMIS 조회 중…"
            KamisClient.fetchAsync(this, force = true) { r ->
                if (r.ok) {
                    marketRef = MarketRef.effective(this)
                    updateLiveStatus()
                    recalc()
                    Toast.makeText(this, "KAMIS 시세 반영", Toast.LENGTH_SHORT).show()
                } else {
                    marketRef = MarketRef.effective(this)
                    updateLiveStatus()
                    Toast.makeText(this, r.error ?: "KAMIS 실패 — 공개 시세 유지", Toast.LENGTH_LONG).show()
                }
            }
        }
        binding.btnOpenMap.setOnClickListener {
            val sizeId = sizes.getOrElse(binding.spinnerSizeA.selectedItemPosition) { EggSize.TEUK }.id
            startActivity(
                Intent(this, MapActivity::class.java)
                    .putExtra(MapActivity.EXTRA_SIZE_ID, sizeId),
            )
        }
        binding.switchSameSize.setOnCheckedChangeListener { _, checked ->
            sameSizeOnly = checked
            refreshHistorySpinners()
            recalc()
        }
        sameSizeOnly = binding.switchSameSize.isChecked
    }

    private fun updateKamisUi() {
        binding.layoutKamisDetail.visibility =
            if (kamisExpanded) View.VISIBLE else View.GONE
        binding.textKamisChevron.text = if (kamisExpanded) "▲" else "▼"
        binding.textKamisHeader.text =
            if (kamisExpanded) "고급 · KAMIS 설정" else "고급 · KAMIS 설정 (선택)"
        binding.textKamisHint.visibility =
            if (kamisExpanded) View.GONE else View.VISIBLE
        binding.rowKamisHeader.contentDescription =
            if (kamisExpanded) "KAMIS 설정 접기" else "KAMIS 설정 펼치기"
    }

    private fun updateLiveStatus() {
        marketRef = MarketRef.effective(this)
        val daily = if (DailyMarketGate.refreshedToday(this)) {
            "오늘 반영 완료"
        } else {
            "오늘 미갱신 · 1회 받기 가능"
        }
        binding.textLiveStatus.text =
            "${marketRef.asOf} · ${marketRef.source}\n$daily · 하루 1회 정책"
        binding.btnFetchLive.isEnabled = !DailyMarketGate.refreshedToday(this)
        binding.btnFetchLive.text =
            if (DailyMarketGate.refreshedToday(this)) "오늘 반영됨" else "오늘 시세 받기"
        updatePriceMarketHint()
        refreshTrendUi()
    }

    private fun setupTrendUi() {
        binding.btnUseLocation.setOnClickListener {
            locationPurpose = "region"
            val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
                resolveRegionFromLocation()
            } else {
                locationPermission.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            }
        }
        binding.spinnerRegion.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressRegionSelect) return
                val name = parent?.getItemAtPosition(position)?.toString() ?: return
                selectedRegion = name
                regionPrefs.edit().putString("region", name).apply()
                bindTrendRegion(name)
                // 지역 바꾸면 매수 코멘트(추세) 다시 반영
                lastResult?.let { showOpinion(it) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        refreshTrendUi()
    }

    private fun refreshTrendUi() {
        val trend = marketRef.trend
        val names = trend?.regionNames()?.ifEmpty { listOf(RegionLocator.DEFAULT) }
            ?: listOf(RegionLocator.DEFAULT)
        suppressRegionSelect = true
        binding.spinnerRegion.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val idx = names.indexOf(selectedRegion).let { if (it >= 0) it else 0 }
        selectedRegion = names.getOrElse(idx) { RegionLocator.DEFAULT }
        binding.spinnerRegion.setSelection(idx)
        suppressRegionSelect = false
        bindTrendRegion(selectedRegion)
    }

    private fun bindTrendRegion(region: String) {
        val trend = marketRef.trend
        val r = trend?.get(region)
        if (trend == null || r == null) {
            binding.textTrendHeadline.text = "추세 데이터 없음"
            binding.textTrendMeta.text = "시세 피드를 받은 뒤 다시 확인해 주세요."
            binding.trendSpark.setSeries(emptyList())
            binding.textTrendFoot.text = "특란 30구 지역 추세는 피드의 trend 항목을 사용합니다."
            return
        }
        binding.textTrendHeadline.text = "${r.name} · ${formatWon(r.latest)}"
        val ch = r.changePct()
        val chText = when {
            ch == null -> "기간 비교 없음"
            ch > 0.5 -> "기간 대비 약 +${ch.roundToInt()}%"
            ch < -0.5 -> "기간 대비 약 ${ch.roundToInt()}%"
            else -> "기간 대비 보합"
        }
        val first = r.series.firstOrNull()?.date?.takeLast(5)?.replace("-", "/") ?: ""
        val last = r.series.lastOrNull()?.date?.takeLast(5)?.replace("-", "/") ?: trend.asOf
        binding.textTrendMeta.text =
            "${trend.product} · ${trend.unit}\n$first ~ $last · $chText"
        binding.trendSpark.setSeries(r.series.map { it.price })
        binding.textTrendFoot.text = "출처: ${trend.source.ifBlank { marketRef.source }}"
    }

    @SuppressLint("MissingPermission")
    private fun resolveRegionFromLocation() {
        Toast.makeText(this, "위치 확인 중…", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val lm = getSystemService(LOCATION_SERVICE) as LocationManager
                val providers = listOf(
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.GPS_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER,
                )
                val loc = providers.firstNotNullOfOrNull { p ->
                    try {
                        if (lm.isProviderEnabled(p)) lm.getLastKnownLocation(p) else null
                    } catch (_: Exception) {
                        null
                    }
                }
                if (loc == null) {
                    runOnUiThread {
                        Toast.makeText(this, "위치를 못 찾았어요. 지역을 직접 골라 주세요.", Toast.LENGTH_LONG).show()
                    }
                    return@thread
                }
                @Suppress("DEPRECATION")
                val geo = Geocoder(this, Locale.KOREA)
                @Suppress("DEPRECATION")
                val list = geo.getFromLocation(loc.latitude, loc.longitude, 1)
                val addr = list?.firstOrNull()
                val region = RegionLocator.fromAddress(
                    addr?.adminArea,
                    addr?.locality,
                    addr?.subAdminArea,
                )
                runOnUiThread {
                    val names = marketRef.trend?.regionNames().orEmpty()
                    val (pick, detected) = RegionLocator.pickAvailable(region, names)
                    selectedRegion = pick
                    regionPrefs.edit().putString("region", pick).apply()
                    refreshTrendUi()
                    lastResult?.let { showOpinion(it) }
                    val msg = when {
                        pick == detected -> "지역: $pick"
                        pick == RegionLocator.DEFAULT ->
                            "$detected 시세 없음 → 전국 (근처 권역도 없음)"
                        else ->
                            "$detected → 가까운 시세 권역 $pick 적용"
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "위치 변환 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupSpinners() {
        val sizeLabels = sizes.map { "${it.label} (약 ${it.midG.toInt()}g)" }
        val sizeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sizeLabels)
        binding.spinnerSizeA.adapter = sizeAdapter
        binding.spinnerSizeB.adapter = sizeAdapter
        binding.spinnerSizeA.setSelection(sizes.indexOf(EggSize.TEUK))
        binding.spinnerSizeB.setSelection(sizes.indexOf(EggSize.DAE))

        val modes = listOf("대표값 (권장)", "범위 하한 (보수적)", "직접 입력 (g/개)")
        val modeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes)
        binding.spinnerModeA.adapter = modeAdapter
        binding.spinnerModeB.adapter = modeAdapter
    }

    /** 웹과 동일 스케일: 소란~왕란 시각적 크기 차별 */
    private fun eggScale(size: EggSize): Float = when (size) {
        EggSize.SO -> 0.72f
        EggSize.JUNG -> 0.82f
        EggSize.DAE -> 0.92f
        EggSize.TEUK -> 1.0f
        EggSize.WANG -> 1.06f
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun setupSizeEggChips() {
        buildEggPicker(binding.eggPickerA, binding.spinnerSizeA)
        buildEggPicker(binding.eggPickerB, binding.spinnerSizeB)
        refreshEggPickerSelection(binding.eggPickerA, binding.spinnerSizeA.selectedItemPosition)
        refreshEggPickerSelection(binding.eggPickerB, binding.spinnerSizeB.selectedItemPosition)
    }

    private fun buildEggPicker(container: LinearLayout, spinner: android.widget.Spinner) {
        container.removeAllViews()
        val gap = dp(4f)
        // 화면 너비에 5등분 — 좌우 여백 균형
        sizes.forEachIndexed { index, size ->
            val scale = eggScale(size)
            val eggW = dp(32f * scale)
            val eggH = dp(42f * scale)
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                isFocusable = true
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_egg_opt)
                setPadding(dp(4f), dp(8f), dp(4f), dp(8f))
                tag = size
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ).also { lp ->
                    if (index > 0) lp.marginStart = gap
                }
            }
            val icon = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(eggW, eggH).also {
                    it.gravity = Gravity.CENTER_HORIZONTAL
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageResource(R.drawable.ic_egg_picker)
                tag = "egg_icon"
            }
            val name = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).also {
                    it.topMargin = dp(4f)
                    it.gravity = Gravity.CENTER_HORIZONTAL
                }
                text = size.label
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                paint.isFakeBoldText = true
                tag = "egg_name"
            }
            val grams = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).also {
                    it.topMargin = dp(1f)
                    it.gravity = Gravity.CENTER_HORIZONTAL
                }
                text = "~${size.midG.toInt()}g"
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            }
            col.addView(icon)
            col.addView(name)
            col.addView(grams)
            col.setOnClickListener {
                spinner.setSelection(index)
                refreshEggPickerSelection(container, index)
                recalc()
            }
            container.addView(col)
        }
    }

    private fun refreshEggPickerSelection(container: LinearLayout, position: Int) {
        val sel = sizes.getOrElse(position) { EggSize.TEUK }
        for (i in 0 until container.childCount) {
            val col = container.getChildAt(i) as? LinearLayout ?: continue
            val size = col.tag as? EggSize ?: continue
            val on = size == sel
            col.isSelected = on
            val icon = col.findViewWithTag<ImageView>("egg_icon")
            icon?.setImageResource(
                if (on) R.drawable.ic_egg_picker_on else R.drawable.ic_egg_picker,
            )
            val name = col.findViewWithTag<TextView>("egg_name")
            name?.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (on) R.color.accent_press else R.color.ink,
                ),
            )
        }
    }

    private fun syncEggChipsFromSpinner(
        picker: LinearLayout,
        position: Int,
    ) {
        refreshEggPickerSelection(picker, position)
    }

    private fun setupCountChips() {
        val presetChips = listOf(
            binding.chip10 to 10,
            binding.chip15 to 15,
            binding.chip20 to 20,
            binding.chip30 to 30,
        )
        presetChips.forEach { (chip, n) ->
            chip.setOnClickListener {
                presetChips.forEach { (c, _) -> c.isChecked = false }
                binding.chipCountOther.isChecked = false
                chip.isChecked = true
                binding.editCountA.setText(n.toString())
                binding.layoutCountCustom.visibility = View.GONE
                refreshCountChipStyles()
                recalc()
            }
        }
        binding.chipCountOther.setOnClickListener {
            presetChips.forEach { (c, _) -> c.isChecked = false }
            binding.chipCountOther.isChecked = true
            binding.layoutCountCustom.visibility = View.VISIBLE
            // 기타: 기존 값이 프리셋이면 비우거나 유지 — 입력 유도
            val cur = binding.editCountA.text?.toString()?.toIntOrNull()
            if (cur == null || cur in listOf(10, 15, 20, 30)) {
                binding.editCountA.setText("")
            }
            binding.editCountA.requestFocus()
            refreshCountChipStyles()
            recalc()
        }
        // 기본: 30 한판, 직접 입력란 숨김
        applyCountSelection(30)
    }

    /** 프리셋이면 칩만, 그 외 개수는 기타 + 입력란 표시 */
    private fun applyCountSelection(count: Int) {
        val presets = mapOf(
            10 to binding.chip10,
            15 to binding.chip15,
            20 to binding.chip20,
            30 to binding.chip30,
        )
        listOf(
            binding.chip10, binding.chip15, binding.chip20,
            binding.chip30, binding.chipCountOther,
        ).forEach { it.isChecked = false }
        binding.editCountA.setText(count.toString())
        val chip = presets[count]
        if (chip != null) {
            chip.isChecked = true
            binding.layoutCountCustom.visibility = View.GONE
        } else {
            binding.chipCountOther.isChecked = true
            binding.layoutCountCustom.visibility = View.VISIBLE
        }
        refreshCountChipStyles()
    }

    /** 선택 칩 강조 (색 state list + 타입 굵기) */
    private fun refreshCountChipStyles() {
        val all = listOf(
            binding.chip10, binding.chip15, binding.chip20,
            binding.chip30, binding.chipCountOther,
        )
        all.forEach { chip ->
            chip.typeface = Typeface.create(
                Typeface.DEFAULT,
                if (chip.isChecked) Typeface.BOLD else Typeface.NORMAL,
            )
            chip.elevation =
                if (chip.isChecked) 3f * resources.displayMetrics.density else 0f
        }
    }

    private fun setupTabs() {
        binding.tabGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            binding.panelCompare.visibility =
                if (checkedId == R.id.tabCompare) View.VISIBLE else View.GONE
            // 비교 탭이면 상품 A 패널도 보이도록 (한 상품 카드 유지)
            binding.panelSingle.visibility = View.VISIBLE
            applySegmentEmphasis(
                first = binding.tabSingle,
                second = binding.tabCompare,
                firstSelected = checkedId == R.id.tabSingle,
            )
            recalc()
        }
        binding.tabGroup.check(R.id.tabSingle)
        applySegmentEmphasis(
            first = binding.tabSingle,
            second = binding.tabCompare,
            firstSelected = true,
        )
    }

    private fun setupShellMode() {
        binding.btnExcludeShell.setOnClickListener {
            excludeShell = true
            updateShellUi()
            recalc()
        }
        binding.btnIncludeShell.setOnClickListener {
            excludeShell = false
            updateShellUi()
            recalc()
        }
        binding.rowEdibleRatioHeader.setOnClickListener {
            edibleRatioExpanded = !edibleRatioExpanded
            updateShellUi()
        }
    }

    /** Selected: bold + slight elevation; unselected: normal (bg/text via color state lists) */
    private fun applySegmentEmphasis(
        first: MaterialButton,
        second: MaterialButton,
        firstSelected: Boolean,
    ) {
        val on = if (firstSelected) first else second
        val off = if (firstSelected) second else first
        on.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        off.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        val elev = 2f * resources.displayMetrics.density
        on.elevation = elev
        off.elevation = 0f
    }

    private fun updateShellUi() {
        binding.btnExcludeShell.isChecked = excludeShell
        binding.btnIncludeShell.isChecked = !excludeShell
        applySegmentEmphasis(
            first = binding.btnExcludeShell,
            second = binding.btnIncludeShell,
            firstSelected = excludeShell,
        )
        binding.layoutShellHelp.visibility = if (excludeShell) View.VISIBLE else View.GONE
        binding.textShellIncludeNote.visibility = if (excludeShell) View.GONE else View.VISIBLE
        val pct = (edibleRatio * 100).roundToInt()
        binding.textEdibleLabel.text = "알맹이 비율 ${pct}% (껍질 약 ${100 - pct}%)"
        binding.textShellHelpBody.text = getString(R.string.shell_help_body, pct, 100 - pct)
        // 접힌 상태에서도 현재 비율이 보이도록 헤더에 표시
        binding.textEdibleRatioHeader.text = "알맹이 비율 설정 · ${pct}%"
        binding.layoutEdibleRatioDetail.visibility =
            if (excludeShell && edibleRatioExpanded) View.VISIBLE else View.GONE
        binding.textEdibleRatioChevron.text = if (edibleRatioExpanded) "▲" else "▼"
        binding.rowEdibleRatioHeader.contentDescription =
            if (edibleRatioExpanded) "알맹이 비율 설정 접기" else "알맹이 비율 설정 펼치기"
        updatePriceMarketHint()
    }

    private fun setupListeners() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = recalc()
        }
        listOf(
            binding.editPriceA, binding.editCountA, binding.editCustomGA,
            binding.editPriceB, binding.editCountB, binding.editCustomGB,
        ).forEach { it.addTextChangedListener(watcher) }

        val spinnerListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateCustomVisibility()
                if (parent === binding.spinnerSizeA) {
                    syncEggChipsFromSpinner(binding.eggPickerA, position)
                    refreshHistorySpinners()
                }
                if (parent === binding.spinnerSizeB) {
                    syncEggChipsFromSpinner(binding.eggPickerB, position)
                }
                recalc()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        listOf(
            binding.spinnerSizeA, binding.spinnerModeA,
            binding.spinnerSizeB, binding.spinnerModeB,
        ).forEach { it.onItemSelectedListener = spinnerListener }

        binding.sliderEdible.addOnChangeListener { _, value, _ ->
            edibleRatio = value / 100.0
            updateShellUi()
            recalc()
        }
        binding.sliderEdible.value = 89f
    }

    private fun setupSaveAndHistory() {
        binding.btnSave.setOnClickListener { requestSave() }
        binding.btnClearHistory.setOnClickListener {
            historyStore.clear()
            refreshHistorySpinners()
            Toast.makeText(this, "이력을 모두 삭제했어요", Toast.LENGTH_SHORT).show()
        }
        binding.btnShareHistory.setOnClickListener { shareSelectedHistory() }
        binding.spinnerShareHistory.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    if (suppressShareSelect) return
                    binding.btnShareHistory.isEnabled = position > 0 && shareHistoryEntries.isNotEmpty()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {
                    binding.btnShareHistory.isEnabled = false
                }
            }

        val histListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressHistorySelect || position <= 0) return
                val entry = historyEntries.getOrNull(position - 1) ?: return
                val toB = parent === binding.spinnerHistoryB
                applyHistory(entry, toB = toB)
                Toast.makeText(
                    this@MainActivity,
                    if (toB) "상품 B에 넣었어요" else "상품 A에 넣었어요",
                    Toast.LENGTH_SHORT,
                ).show()
                // 선택 초기화
                suppressHistorySelect = true
                parent?.setSelection(0)
                suppressHistorySelect = false
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.spinnerHistoryA.onItemSelectedListener = histListener
        binding.spinnerHistoryB.onItemSelectedListener = histListener
    }

    private fun refreshHistorySpinners() {
        val focus = sizes.getOrElse(binding.spinnerSizeA.selectedItemPosition) { EggSize.TEUK }
        historyEntries = historyStore.loadAll().let { all ->
            if (sameSizeOnly) all.filter { it.sizeId == focus.id } else all
        }
        val labels = mutableListOf("선택… (${historyEntries.size}건 · ${if (sameSizeOnly) focus.label else "전체"})")
        historyEntries.forEach { e ->
            labels.add(historyLabel(e))
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        suppressHistorySelect = true
        binding.spinnerHistoryA.adapter = adapter
        binding.spinnerHistoryB.adapter = adapter
        suppressHistorySelect = false

        // 공유: 전체 이력 (사이즈 필터 없음)
        shareHistoryEntries = historyStore.loadAll()
        val shareLabels = mutableListOf(
            if (shareHistoryEntries.isEmpty()) "저장된 이력 없음"
            else "공유할 이력 선택… (${shareHistoryEntries.size}건)",
        )
        shareHistoryEntries.forEach { e -> shareLabels.add(historyLabel(e)) }
        suppressShareSelect = true
        binding.spinnerShareHistory.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, shareLabels)
        binding.spinnerShareHistory.setSelection(0)
        suppressShareSelect = false
        binding.btnShareHistory.isEnabled = false
    }

    private fun historyLabel(e: PriceHistoryEntry): String {
        val note = if (e.note.isNotBlank()) " · ${e.note}" else ""
        val loc = if (e.locationLabel.isNotBlank() && e.locationLabel != "위치 없음") {
            " · ${e.locationLabel}"
        } else {
            ""
        }
        return "${dateFmt.format(Date(e.savedAt))}$note$loc · ${e.sizeLabel} ${e.count}개 ${formatWon(e.priceWon)}"
    }

    private fun shareSelectedHistory() {
        val pos = binding.spinnerShareHistory.selectedItemPosition
        if (pos <= 0) {
            Toast.makeText(this, "공유할 저장 이력을 먼저 선택하세요", Toast.LENGTH_SHORT).show()
            return
        }
        val entry = shareHistoryEntries.getOrNull(pos - 1)
        if (entry == null) {
            Toast.makeText(this, "이력을 찾을 수 없어요", Toast.LENGTH_SHORT).show()
            return
        }
        val text = buildShareText(entry)
        val vs = marketCompareForShare(entry)
        val subjectExtra = vs?.shortLabel?.let { " · $it" }.orEmpty()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "계란 가격 메모 · ${entry.sizeLabel}$subjectExtra")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            startActivity(Intent.createChooser(send, "카톡·문자 등으로 공유"))
        } catch (_: Exception) {
            Toast.makeText(this, "공유할 앱을 열 수 없어요", Toast.LENGTH_SHORT).show()
        }
    }

    /** 공유용 시세 대비 (현재 앱 피드 기준) */
    private data class ShareMarketVs(
        val shortLabel: String,
        val headline: String,
        val detailLine: String,
    )

    private fun marketCompareForShare(e: PriceHistoryEntry): ShareMarketVs? {
        val size = EggSize.fromId(e.sizeId)
        val pair = MarketBench.marketPer10g(size, e.excludeShell, e.edibleRatio, marketRef)
            ?: return null
        val marketPer10 = pair.first
        if (marketPer10 <= 0) return null
        val pct = ((e.per10g - marketPer10) / marketPer10) * 100.0
        val (shortLabel, verdict, arrow) = when {
            pct <= -15 -> Triple("매우 저렴", "시세보다 훨씬 저렴해요", "▼")
            pct <= -5 -> Triple("저렴", "시세보다 저렴해요", "▼")
            pct < 5 -> Triple("시세 수준", "평균시세와 비슷해요", "●")
            pct < 15 -> Triple("조금 비쌈", "시세보다 조금 비싸요", "▲")
            else -> Triple("비쌈", "시세보다 비싸요", "▲")
        }
        val sign = if (pct > 0) "+" else ""
        val headline = "$arrow $verdict (${sign}${pct.roundToInt()}%)"
        val detailLine =
            "• 시세 대비: $arrow ${sign}${pct.roundToInt()}% · $shortLabel (평균시세 ${formatWon(marketPer10)}/10g)"
        return ShareMarketVs(shortLabel = shortLabel, headline = headline, detailLine = detailLine)
    }

    private fun buildShareText(e: PriceHistoryEntry): String {
        val whenStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(e.savedAt))
        val mode = if (e.excludeShell) "알맹이(껍질 제외)" else "껍질 포함"
        val mapUrl = if (e.lat != null && e.lng != null) {
            "https://maps.google.com/?q=${e.lat},${e.lng}"
        } else {
            null
        }
        val vs = marketCompareForShare(e)
        return buildString {
            appendLine("🥚 계란 가격 메모")
            // 카톡에서 바로 보이도록 상단에 시세 대비
            if (vs != null) {
                appendLine(vs.headline)
                appendLine()
            }
            appendLine("• 날짜: $whenStr")
            appendLine("• 사이즈: ${e.sizeLabel} · ${e.count}개")
            appendLine("• 가격: ${formatWon(e.priceWon)}")
            appendLine("• 10g당: ${formatWon(e.per10g)} ($mode)")
            if (vs != null) {
                appendLine(vs.detailLine)
            }
            appendLine("• 개당: ${formatWon(e.perEgg)}")
            if (e.note.isNotBlank()) appendLine("• 매장·메모: ${e.note}")
            appendLine("• 위치: ${e.locationLabel.ifBlank { "위치 없음" }}")
            if (mapUrl != null) appendLine("• 지도: $mapUrl")
            appendLine()
            append("계란 단가 계산기에서 공유")
        }
    }

    private fun applyHistory(e: PriceHistoryEntry, toB: Boolean) {
        val size = EggSize.fromId(e.sizeId)
        val sizeIdx = sizes.indexOf(size).coerceAtLeast(0)
        val modePos = when (e.weightMode) {
            "min" -> 1
            "custom" -> 2
            else -> 0
        }
        excludeShell = e.excludeShell
        edibleRatio = e.edibleRatio
        binding.sliderEdible.value = (e.edibleRatio * 100).toFloat().coerceIn(85f, 92f)
        updateShellUi()

        if (toB) {
            binding.spinnerSizeB.setSelection(sizeIdx)
            binding.editCountB.setText(e.count.toString())
            binding.editPriceB.setText(e.priceWon.toInt().toString())
            binding.spinnerModeB.setSelection(modePos)
            e.customG?.let { binding.editCustomGB.setText(it.toString()) }
            binding.tabGroup.check(R.id.tabCompare)
            binding.panelCompare.visibility = View.VISIBLE
        } else {
            binding.spinnerSizeA.setSelection(sizeIdx)
            applyCountSelection(e.count)
            binding.editPriceA.setText(e.priceWon.toInt().toString())
            binding.spinnerModeA.setSelection(modePos)
            e.customG?.let { binding.editCustomGA.setText(it.toString()) }
            if (e.note.isNotBlank()) binding.editNote.setText(e.note)
        }
        updateCustomVisibility()
        recalc()
    }

    private fun requestSave() {
        if (lastResult == null) {
            Toast.makeText(this, "먼저 가격을 계산하세요", Toast.LENGTH_SHORT).show()
            return
        }
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            saveCurrent(withLocation = true)
        } else {
            locationPurpose = "save"
            locationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ),
            )
        }
    }

    private fun saveCurrent(withLocation: Boolean) {
        val r = lastResult ?: return
        val input = readA() ?: return
        binding.textSaveStatus.text = "저장 중…"
        binding.btnSave.isEnabled = false

        var lat: Double? = null
        var lng: Double? = null
        var label = "위치 없음"
        if (withLocation) {
            val loc = PriceHistoryStore.lastKnownLocation(this)
            if (loc != null) {
                lat = loc.latitude
                lng = loc.longitude
                label = PriceHistoryStore.reverseLabel(this, lat, lng)
            }
        }

        val modeStr = when (input.weightMode) {
            WeightMode.MIN -> "min"
            WeightMode.CUSTOM -> "custom"
            else -> "mid"
        }
        val entry = PriceHistoryEntry(
            id = PriceHistoryStore.newId(),
            savedAt = System.currentTimeMillis(),
            lat = lat,
            lng = lng,
            locationLabel = label,
            note = binding.editNote.text?.toString()?.trim().orEmpty(),
            sizeId = r.size.id,
            sizeLabel = r.size.label,
            count = r.count,
            priceWon = r.priceWon,
            weightMode = modeStr,
            customG = input.customGrams,
            excludeShell = r.excludeShell,
            edibleRatio = r.edibleRatio,
            unitG = r.unitG,
            per10g = r.per10g,
            perEgg = r.perEgg,
        )
        historyStore.save(entry)
        refreshHistorySpinners()
        binding.textSaveStatus.text =
            "저장됨 · $label · ${dateFmt.format(Date(entry.savedAt))}"
        binding.btnSave.isEnabled = true
        Toast.makeText(this, "가격을 저장했어요", Toast.LENGTH_SHORT).show()
    }

    private fun updateCustomVisibility() {
        binding.layoutCustomA.visibility =
            if (binding.spinnerModeA.selectedItemPosition == 2) View.VISIBLE else View.GONE
        binding.layoutCustomB.visibility =
            if (binding.spinnerModeB.selectedItemPosition == 2) View.VISIBLE else View.GONE
    }

    private fun weightMode(pos: Int): WeightMode = when (pos) {
        1 -> WeightMode.MIN
        2 -> WeightMode.CUSTOM
        else -> WeightMode.MID
    }

    private fun parseDouble(s: String?): Double? =
        s?.trim()?.replace(",", "")?.toDoubleOrNull()

    private fun readA(): CalcInput? {
        val price = parseDouble(binding.editPriceA.text?.toString()) ?: return null
        val count = binding.editCountA.text?.toString()?.toIntOrNull() ?: return null
        return CalcInput(
            size = sizes[binding.spinnerSizeA.selectedItemPosition],
            count = count,
            priceWon = price,
            weightMode = weightMode(binding.spinnerModeA.selectedItemPosition),
            customGrams = parseDouble(binding.editCustomGA.text?.toString()),
            excludeShell = excludeShell,
            edibleRatio = edibleRatio,
        )
    }

    private fun readB(): CalcInput? {
        val price = parseDouble(binding.editPriceB.text?.toString()) ?: return null
        val count = binding.editCountB.text?.toString()?.toIntOrNull() ?: return null
        return CalcInput(
            size = sizes[binding.spinnerSizeB.selectedItemPosition],
            count = count,
            priceWon = price,
            weightMode = weightMode(binding.spinnerModeB.selectedItemPosition),
            customGrams = parseDouble(binding.editCustomGB.text?.toString()),
            excludeShell = excludeShell,
            edibleRatio = edibleRatio,
        )
    }

    private fun basisTitle(r: CalcResult): String =
        if (r.excludeShell) "알맹이(껍질 제외) 10g당" else "전체(껍질 포함) 10g당"

    private fun selectedSizeA(): EggSize =
        sizes.getOrElse(binding.spinnerSizeA.selectedItemPosition) { EggSize.TEUK }

    /** 가격 입력란 아래: 오늘(피드) 평균 팩가·10g당 시세 */
    private fun updatePriceMarketHint() {
        val size = selectedSizeA()
        val pair = MarketBench.marketPer10g(size, excludeShell, edibleRatio, marketRef)
        if (pair == null) {
            binding.textPriceMarketHint.text = "시세 정보를 불러오지 못했어요"
            return
        }
        val (per10, pack) = pair
        val packN = marketRef.packCount
        binding.textPriceMarketHint.text =
            "${size.label} 오늘 참고 시세 약 ${formatWon(pack)}/${packN}개 · 10g당 약 ${formatWon(per10)}"
    }

    /** 가격 미입력 시 결과 카드에 시세 10g당 표시 */
    private fun showMarketAverageResult() {
        lastResult = null
        binding.btnSave.isEnabled = false
        hideOpinion()
        clearStockVsMarket()
        val size = selectedSizeA()
        val pair = MarketBench.marketPer10g(size, excludeShell, edibleRatio, marketRef)
        if (pair == null) {
            binding.textResultTitle.text = "가격을 입력하세요"
            binding.textResultMain.text = "—"
            binding.textResultDetail.text = "사이즈 · 개수 · 가격을 넣으면 10g당 단가가 나옵니다."
            return
        }
        val (per10, pack) = pair
        val mode = if (excludeShell) "알맹이 기준" else "껍질 포함"
        binding.textResultTitle.text = "시세 10g당 ($mode · 참고)"
        binding.textResultMain.text = formatWon(per10)
        binding.textResultDetail.text = buildString {
            append("${size.label} · 30개 팩 기준 환산\n")
            append("오늘 참고 팩가 ${formatWon(pack)}\n")
            append("가격을 입력하면 내 단가와 평균시세를 비교해요")
        }
        updatePriceMarketHint()
    }

    /** 주가형: 내 10g당 옆 평균시세 + 등락% */
    private fun showStockVsMarket(op: MarketOpinion) {
        binding.layoutResultVsMarket.visibility = View.VISIBLE
        binding.textResultMarketAvg.text = "평균시세 ${formatWon(op.marketPer10g)}"
        val pct = op.pctDiff
        val abs = kotlin.math.abs(pct).roundToInt()
        val (arrow, label, colorRes) = when {
            pct <= -5 -> Triple("▼", "싸다", R.color.result_cheap)
            pct >= 5 -> Triple("▲", "비싸다", R.color.result_expensive)
            else -> Triple("●", "시세 수준", R.color.result_fair)
        }
        val sign = if (pct > 0) "+" else ""
        binding.textResultChange.text = "$arrow ${sign}${pct.roundToInt()}% · $label"
        binding.textResultChange.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun clearStockVsMarket() {
        binding.layoutResultVsMarket.visibility = View.GONE
        binding.textResultMarketAvg.text = "평균시세 —"
        binding.textResultChange.text = "—"
    }

    private fun showOpinion(r: CalcResult) {
        val op = MarketBench.opinion(r, marketRef) ?: run {
            hideOpinion()
            clearStockVsMarket()
            return
        }
        showStockVsMarket(op)
        binding.layoutMarketOpinion.visibility = View.VISIBLE
        val sign = if (op.pctDiff > 0) "+" else ""
        val live = if (marketRef.live) " LIVE" else ""
        binding.textMarketBadge.text = "${op.label}$live  ${sign}${op.pctDiff.roundToInt()}%"
        // 핵심 한 줄 — 내 단가 vs 평균 (글씨 크게)
        binding.textMarketDetail.text =
            "내 ${formatWon(op.minePer10g)}  ·  평균시세 ${formatWon(op.marketPer10g)}"
        val advice = buildPurchaseAdvice(op)
        if (advice.isNotBlank()) {
            binding.textMarketAdvice.visibility = View.VISIBLE
            binding.textMarketAdvice.text = advice
        } else {
            binding.textMarketAdvice.visibility = View.GONE
        }
        binding.textMarketMeta.text =
            "${r.size.label} 30개 팩 ${formatWon(op.packPrice)} · ${op.asOf}"
    }

    /**
     * 평균시세 대비 + 선택 지역 추세로 짧은 매수/관망 코멘트.
     * (투자 조언이 아니라 장보기 참고용 톤)
     */
    private fun buildPurchaseAdvice(op: MarketOpinion): String {
        val region = selectedRegion
        val trend = marketRef.trend?.get(region)
        val ch = trend?.changePct()
        val trendPart = when {
            ch == null -> null
            ch > 1.0 -> "${region} 특란 추세가 오르는 중"
            ch < -1.0 -> "${region} 특란 추세가 내려가는 중"
            else -> "${region} 특란 추세는 보합"
        }
        val pricePart = when (op.tone) {
            "cheap" -> when {
                ch != null && ch > 1.0 ->
                    "시세보다 저렴한데 지역가가 오르고 있어요. 지금 담아두면 괜찮은 타이밍이에요."
                ch != null && ch < -1.0 ->
                    "시세보다 싸요. 지역가도 내려가는 중이라 조금 더 지켜봐도 되고, 지금 사도 손해 보긴 어려워요."
                else ->
                    "평균시세보다 저렴한 편이에요. 장보기 매수 타이밍으로 무난해요."
            }
            "expensive" -> when {
                ch != null && ch > 1.0 ->
                    "이미 시세보다 비싸고 지역가도 오르는 중이에요. 다른 매장·날짜를 한 번 더 보면 좋아요."
                ch != null && ch < -1.0 ->
                    "시세보다 비싸지만 지역가는 내려가는 중이에요. 급하지 않으면 조금 기다려 보세요."
                else ->
                    "평균시세보다 비싼 편이에요. 급하면 사고, 여유 있으면 다른 매장을 비교해 보세요."
            }
            else -> when {
                ch != null && ch > 1.0 ->
                    "가격은 시세 수준이에요. 지역가가 오르는 중이라 재고 필요할 때 사도 괜찮아요."
                ch != null && ch < -1.0 ->
                    "가격은 시세 수준이고 지역가는 내려가는 중이에요. 급하지 않으면 조금 더 지켜봐도 돼요."
                else ->
                    "평균시세와 비슷한 수준이에요. 필요한 만큼만 사면 돼요."
            }
        }
        return if (trendPart != null) "$trendPart. $pricePart" else pricePart
    }

    private fun hideOpinion() {
        binding.layoutMarketOpinion.visibility = View.GONE
        binding.textMarketAdvice.visibility = View.GONE
        binding.textMarketAdvice.text = ""
    }

    private fun recalc() {
        updateCustomVisibility()
        updatePriceMarketHint()
        val compareMode = binding.panelCompare.visibility == View.VISIBLE &&
            binding.tabGroup.checkedButtonId == R.id.tabCompare

        if (!compareMode) {
            val input = readA()
            if (input == null) {
                // 가격 비어 있으면 해당 사이즈 시세 10g당 표시
                showMarketAverageResult()
                return
            }
            EggCalculator.calculate(input).fold(
                onSuccess = { r ->
                    lastResult = r
                    binding.btnSave.isEnabled = true
                    binding.textResultTitle.text = basisTitle(r)
                    binding.textResultMain.text = formatWon(r.per10g)
                    binding.textResultDetail.text = buildString {
                        append("${r.size.label} · ${fmt(r.unitG)}g/개 · ${r.count}개 · ${formatWon(r.priceWon)}\n")
                        if (r.excludeShell) {
                            append("알맹이 ${(r.edibleRatio * 100).roundToInt()}% · 총 ${fmt(r.totalUsableG)}g · ")
                        } else {
                            append("껍질 포함 총 ${fmt(r.totalUsableG)}g · ")
                        }
                        append("개당 ${formatWon(r.perEgg)}")
                    }
                    showOpinion(r)
                },
                onFailure = {
                    lastResult = null
                    binding.btnSave.isEnabled = false
                    hideOpinion()
                    clearStockVsMarket()
                    binding.textResultTitle.text = "입력 확인"
                    binding.textResultMain.text = "—"
                    binding.textResultDetail.text = it.message ?: "오류"
                },
            )
        } else {
            val ia = readA()
            val ib = readB()
            // 비교 모드에서도 A가 있으면 저장 가능
            if (ia != null) {
                EggCalculator.calculate(ia).onSuccess {
                    lastResult = it
                    binding.btnSave.isEnabled = true
                }
            }
            if (ia == null || ib == null) {
                hideOpinion()
                clearStockVsMarket()
                binding.textResultTitle.text = "비교"
                binding.textResultMain.text = "—"
                binding.textResultDetail.text = "상품 A·B 가격을 모두 입력하세요."
                return
            }
            val ra = EggCalculator.calculate(ia).getOrElse {
                Toast.makeText(this, it.message, Toast.LENGTH_SHORT).show()
                return
            }
            val rb = EggCalculator.calculate(ib).getOrElse {
                Toast.makeText(this, it.message, Toast.LENGTH_SHORT).show()
                return
            }
            val same = MarketBench.sameSize(ra, rb)
            if (sameSizeOnly && !same) {
                binding.textResultTitle.text = "사이즈 불일치"
                binding.textResultMain.text = "같은 호수로 맞춰 주세요"
                binding.textResultDetail.text =
                    "A ${ra.size.label} · B ${rb.size.label}\n같은 사이즈만 옵션이 켜져 있어 시세·승자 비교를 보류해요."
                hideOpinion()
                clearStockVsMarket()
                return
            }
            val c = EggCalculator.compare(ra, rb)
            val mode = if (excludeShell) "알맹이 기준" else "껍질 포함 기준"
            binding.textResultTitle.text =
                if (c.cheaper == "tie") "비슷함 ($mode · ${ra.size.label})"
                else "승자: 상품 ${c.cheaper} ($mode · ${ra.size.label})"
            // 비교 모드: 메인에 승자 문구, 옆 평균시세는 A 기준
            binding.textResultMain.text = when (c.cheaper) {
                "A" -> formatWon(ra.per10g)
                "B" -> formatWon(rb.per10g)
                else -> formatWon(ra.per10g)
            }
            val opA = if (same) MarketBench.opinion(ra, marketRef) else null
            val opB = if (same) MarketBench.opinion(rb, marketRef) else null
            binding.textResultDetail.text = buildString {
                append(c.message)
                append("\n")
                if (!same) append("⚠ 사이즈가 다릅니다. 시세 비교는 동일 호수 권장.\n")
                append("A ${ra.size.label} ${ra.count}개 → 10g당 ${formatWon(ra.per10g)}")
                opA?.let { append(" · ${it.label}") }
                append("\n")
                append("B ${rb.size.label} ${rb.count}개 → 10g당 ${formatWon(rb.per10g)}")
                opB?.let { append(" · ${it.label}") }
            }
            // 비교 시 시세 패널은 더 저렴한 쪽(동률이면 A) 기준
            val focus = when (c.cheaper) {
                "B" -> rb
                else -> ra
            }
            showOpinion(focus)
        }
    }

    private fun formatWon(n: Double): String = "${wonFmt.format(n.roundToInt())}원"
    private fun fmt(n: Double): String =
        if (n % 1.0 == 0.0) n.toInt().toString() else String.format(Locale.KOREA, "%.1f", n)
}
