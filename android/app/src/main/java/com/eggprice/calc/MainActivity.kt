package com.eggprice.calc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.eggprice.calc.databinding.ActivityMainBinding
import com.google.android.material.chip.Chip
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var historyStore: PriceHistoryStore
    private val wonFmt = NumberFormat.getNumberInstance(Locale.KOREA)
    private val sizes = EggSize.entries
    private var edibleRatio: Double = DEFAULT_EDIBLE_RATIO
    private var excludeShell: Boolean = true
    private var lastResult: CalcResult? = null
    private var historyEntries: List<PriceHistoryEntry> = emptyList()
    private var suppressHistorySelect = false
    private var sameSizeOnly: Boolean = true
    private var marketRef: MarketRef = MarketRef()

    private val dateFmt = SimpleDateFormat("M/d HH:mm", Locale.KOREA)

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val ok = granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_FINE_LOCATION] == true
        saveCurrent(withLocation = ok)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        historyStore = PriceHistoryStore(this)
        marketRef = MarketRef.effective(this)

        setupSpinners()
        setupSizeEggChips()
        setupCountChips()
        setupTabs()
        setupShellMode()
        setupListeners()
        setupSaveAndHistory()
        setupKamisAndMap()
        refreshHistorySpinners()
        // 앱 시작: 원격 피드(GitHub raw) 하루 1회 시도 → 실패 시 assets
        if (!DailyMarketGate.refreshedToday(this)) {
            PublicMarketFeed.refreshRemoteAsync(this) {
                marketRef = MarketRef.effective(this)
                if (!DailyMarketGate.refreshedToday(this)) {
                    DailyMarketGate.markRefreshed(this)
                }
                updateLiveStatus()
                recalc()
            }
        }
        marketRef = MarketRef.effective(this)
        updateLiveStatus()
        updateShellUi()
        recalc()
    }

    private fun setupKamisAndMap() {
        val creds = KamisClient.loadCreds(this)
        binding.editKamisKey.setText(creds.certKey)
        binding.editKamisId.setText(creds.certId)

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

    private fun setupSizeEggChips() {
        fun bindGroup(
            chips: List<Pair<Chip, EggSize>>,
            spinner: android.widget.Spinner,
        ) {
            chips.forEach { (chip, size) ->
                chip.text = "🥚\n${size.label}"
                val scale = when (size) {
                    EggSize.SO -> 0.85f
                    EggSize.JUNG -> 0.92f
                    EggSize.DAE -> 1.0f
                    EggSize.TEUK -> 1.05f
                    EggSize.WANG -> 1.12f
                }
                chip.textSize = 11f * scale
                chip.setOnClickListener {
                    chips.forEach { (c, _) -> c.isChecked = false }
                    chip.isChecked = true
                    spinner.setSelection(sizes.indexOf(size))
                    recalc()
                }
            }
            val idx = spinner.selectedItemPosition.coerceIn(0, sizes.lastIndex)
            chips.forEach { (c, s) -> c.isChecked = s == sizes[idx] }
        }

        bindGroup(
            listOf(
                binding.eggChipSoA to EggSize.SO,
                binding.eggChipJungA to EggSize.JUNG,
                binding.eggChipDaeA to EggSize.DAE,
                binding.eggChipTeukA to EggSize.TEUK,
                binding.eggChipWangA to EggSize.WANG,
            ),
            binding.spinnerSizeA,
        )
        bindGroup(
            listOf(
                binding.eggChipSoB to EggSize.SO,
                binding.eggChipJungB to EggSize.JUNG,
                binding.eggChipDaeB to EggSize.DAE,
                binding.eggChipTeukB to EggSize.TEUK,
                binding.eggChipWangB to EggSize.WANG,
            ),
            binding.spinnerSizeB,
        )
    }

    private fun syncEggChipsFromSpinner(
        chips: List<Pair<Chip, EggSize>>,
        position: Int,
    ) {
        val sel = sizes.getOrElse(position) { EggSize.TEUK }
        chips.forEach { (c, s) -> c.isChecked = s == sel }
    }

    private fun setupCountChips() {
        val chips = listOf(
            binding.chip10 to 10,
            binding.chip15 to 15,
            binding.chip20 to 20,
            binding.chip30 to 30,
        )
        chips.forEach { (chip, n) ->
            chip.setOnClickListener {
                binding.editCountA.setText(n.toString())
                chips.forEach { (c, _) -> c.isChecked = false }
                chip.isChecked = true
                recalc()
            }
        }
        binding.chip30.isChecked = true
    }

    private fun setupTabs() {
        binding.tabGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            binding.panelCompare.visibility =
                if (checkedId == R.id.tabCompare) View.VISIBLE else View.GONE
            // 비교 탭이면 상품 A 패널도 보이도록 (한 상품 카드 유지)
            binding.panelSingle.visibility = View.VISIBLE
            recalc()
        }
        binding.tabGroup.check(R.id.tabSingle)
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
    }

    private fun updateShellUi() {
        binding.btnExcludeShell.isChecked = excludeShell
        binding.btnIncludeShell.isChecked = !excludeShell
        binding.layoutShellHelp.visibility = if (excludeShell) View.VISIBLE else View.GONE
        binding.textShellIncludeNote.visibility = if (excludeShell) View.GONE else View.VISIBLE
        val pct = (edibleRatio * 100).roundToInt()
        binding.textEdibleLabel.text = "알맹이 비율 ${pct}% (껍질 약 ${100 - pct}%)"
        binding.textShellHelpBody.text = getString(R.string.shell_help_body, pct, 100 - pct)
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
                    syncEggChipsFromSpinner(
                        listOf(
                            binding.eggChipSoA to EggSize.SO,
                            binding.eggChipJungA to EggSize.JUNG,
                            binding.eggChipDaeA to EggSize.DAE,
                            binding.eggChipTeukA to EggSize.TEUK,
                            binding.eggChipWangA to EggSize.WANG,
                        ),
                        position,
                    )
                    refreshHistorySpinners()
                }
                if (parent === binding.spinnerSizeB) {
                    syncEggChipsFromSpinner(
                        listOf(
                            binding.eggChipSoB to EggSize.SO,
                            binding.eggChipJungB to EggSize.JUNG,
                            binding.eggChipDaeB to EggSize.DAE,
                            binding.eggChipTeukB to EggSize.TEUK,
                            binding.eggChipWangB to EggSize.WANG,
                        ),
                        position,
                    )
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
            val note = if (e.note.isNotBlank()) " · ${e.note}" else ""
            val loc = if (e.locationLabel.isNotBlank()) " · ${e.locationLabel}" else ""
            labels.add(
                "${dateFmt.format(Date(e.savedAt))}$note$loc · ${e.sizeLabel} ${e.count}개 ${formatWon(e.priceWon)}",
            )
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        suppressHistorySelect = true
        binding.spinnerHistoryA.adapter = adapter
        binding.spinnerHistoryB.adapter = adapter
        suppressHistorySelect = false
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
            binding.editCountA.setText(e.count.toString())
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

    private fun showOpinion(r: CalcResult) {
        val op = MarketBench.opinion(r, marketRef) ?: run {
            binding.layoutMarketOpinion.visibility = View.GONE
            return
        }
        binding.layoutMarketOpinion.visibility = View.VISIBLE
        val sign = if (op.pctDiff > 0) "+" else ""
        val live = if (marketRef.live) " LIVE" else ""
        binding.textMarketBadge.text = "${op.label}$live  ${sign}${op.pctDiff.roundToInt()}%"
        binding.textMarketDetail.text = op.detail
        binding.textMarketMeta.text = buildString {
            append("${r.size.label} 동일 사이즈 비교\n")
            append("내 10g당 ${formatWon(op.minePer10g)} · 시세 ${formatWon(op.marketPer10g)}\n")
            append("팩가 30개 ${formatWon(op.packPrice)} · ${op.asOf} · ${op.source}")
        }
    }

    private fun hideOpinion() {
        binding.layoutMarketOpinion.visibility = View.GONE
    }

    private fun recalc() {
        updateCustomVisibility()
        val compareMode = binding.panelCompare.visibility == View.VISIBLE &&
            binding.tabGroup.checkedButtonId == R.id.tabCompare

        if (!compareMode) {
            val input = readA()
            if (input == null) {
                lastResult = null
                binding.btnSave.isEnabled = false
                hideOpinion()
                binding.textResultTitle.text = "가격을 입력하세요"
                binding.textResultMain.text = "—"
                binding.textResultDetail.text = "사이즈 · 개수 · 가격을 넣으면 10g당 단가가 나옵니다."
                return
            }
            EggCalculator.calculate(input).fold(
                onSuccess = { r ->
                    lastResult = r
                    binding.btnSave.isEnabled = true
                    binding.textResultTitle.text = basisTitle(r)
                    binding.textResultMain.text = formatWon(r.per10g)
                    binding.textResultDetail.text = buildString {
                        append("${r.size.label} · ${fmt(r.unitG)}g/개\n")
                        append("${r.count}개 · ${formatWon(r.priceWon)}\n")
                        if (r.excludeShell) {
                            append("알맹이 비율 ${(r.edibleRatio * 100).roundToInt()}% · 총 ${fmt(r.totalUsableG)}g\n")
                        } else {
                            append("껍질 포함 총 ${fmt(r.totalUsableG)}g\n")
                        }
                        append("개당 ${formatWon(r.perEgg)} · 1g당 ${String.format(Locale.KOREA, "%.2f", r.perGram)}원")
                    }
                    showOpinion(r)
                },
                onFailure = {
                    lastResult = null
                    binding.btnSave.isEnabled = false
                    hideOpinion()
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
                return
            }
            val c = EggCalculator.compare(ra, rb)
            val mode = if (excludeShell) "알맹이 기준" else "껍질 포함 기준"
            binding.textResultTitle.text =
                if (c.cheaper == "tie") "비슷함 ($mode · ${ra.size.label})"
                else "승자: 상품 ${c.cheaper} ($mode · ${ra.size.label})"
            binding.textResultMain.text = c.message
            val opA = if (same) MarketBench.opinion(ra, marketRef) else null
            val opB = if (same) MarketBench.opinion(rb, marketRef) else null
            binding.textResultDetail.text = buildString {
                if (!same) append("⚠ 사이즈가 다릅니다. 시세 비교는 동일 호수 권장.\n")
                append("A ${ra.size.label} ${ra.count}개 → 10g당 ${formatWon(ra.per10g)}")
                opA?.let { append(" · ${it.label}") }
                append("\n")
                append("B ${rb.size.label} ${rb.count}개 → 10g당 ${formatWon(rb.per10g)}")
                opB?.let { append(" · ${it.label}") }
            }
            showOpinion(ra)
        }
    }

    private fun formatWon(n: Double): String = "${wonFmt.format(n.roundToInt())}원"
    private fun fmt(n: Double): String =
        if (n % 1.0 == 0.0) n.toInt().toString() else String.format(Locale.KOREA, "%.1f", n)
}
