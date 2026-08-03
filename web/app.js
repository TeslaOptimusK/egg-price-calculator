(function () {
  const {
    SIZES,
    COUNT_PRESETS,
    calculate,
    compare,
    formatWon,
    formatNum,
    eggSvg,
    getSize,
  } = EggCalc;
  const { loadAll, saveEntry, remove, clear, get } = EggStorage;
  const {
    loadMarket,
    saveMarket,
    resetMarket,
    opinionVsMarket,
    sameSizeCheck,
  } = EggMarket;

  let excludeShell = true;
  let lastSingleResult = null;
  let sameSizeOnly = true;

  function focusSizeId() {
    const single = document.querySelector("#panel-single .form-card .size");
    if (single && single.value) return single.value;
    const a = document.querySelector("#panel-compare .form-card[data-side='A'] .size");
    return (a && a.value) || "teuk";
  }

  function fillSizeSelect(sel, defaultId) {
    sel.innerHTML = SIZES.map(
      (s) =>
        `<option value="${s.id}" ${s.id === (defaultId || "teuk") ? "selected" : ""}>${s.label} (약 ${s.midG}g)</option>`
    ).join("");
  }

  function fillEggPicker(picker, select, defaultId) {
    const current = defaultId || select.value || "teuk";
    select.value = current;
    picker.innerHTML = SIZES.map((s) => {
      const on = s.id === current;
      return `<button type="button" class="egg-opt ${on ? "on" : ""}" data-size="${s.id}" role="option" aria-selected="${on}">
        ${eggSvg(s.scale, on)}
        <span class="egg-name">${s.label}</span>
        <span class="egg-g">~${s.midG}g</span>
      </button>`;
    }).join("");
  }

  function refreshPickerVisual(picker, sizeId) {
    picker.querySelectorAll(".egg-opt").forEach((btn) => {
      const on = btn.dataset.size === sizeId;
      btn.classList.toggle("on", on);
      btn.setAttribute("aria-selected", on ? "true" : "false");
      const s = SIZES.find((x) => x.id === btn.dataset.size);
      btn.innerHTML = `${eggSvg(s.scale, on)}
        <span class="egg-name">${s.label}</span>
        <span class="egg-g">~${s.midG}g</span>`;
    });
  }

  function fillCountChips(container, countInput) {
    container.innerHTML = COUNT_PRESETS.map(
      (n) =>
        `<button type="button" class="chip" data-count="${n}">${n === 30 ? "30(한판)" : n}</button>`
    ).join("");
    container.querySelectorAll(".chip").forEach((btn) => {
      btn.addEventListener("click", () => {
        countInput.value = btn.dataset.count;
        container.querySelectorAll(".chip").forEach((c) => c.classList.remove("on"));
        btn.classList.add("on");
        recalc();
      });
    });
    const match = container.querySelector(`[data-count="${countInput.value}"]`);
    if (match) match.classList.add("on");
  }

  function wireCard(card) {
    const size = card.querySelector(".size");
    const picker = card.querySelector(".egg-picker");
    const weightMode = card.querySelector(".weight-mode");
    const customWrap = card.querySelector(".custom-weight");
    const count = card.querySelector(".count");
    const chips = card.querySelector(".count-chips");
    const defaultId = card.dataset.side === "B" ? "dae" : "teuk";

    fillSizeSelect(size, defaultId);
    if (picker) {
      picker.addEventListener("click", (e) => {
        const btn = e.target.closest(".egg-opt");
        if (!btn) return;
        size.value = btn.dataset.size;
        refreshPickerVisual(picker, size.value);
        onSizeChanged();
        recalc();
      });
      fillEggPicker(picker, size, defaultId);
      size.addEventListener("change", () => {
        refreshPickerVisual(picker, size.value);
        onSizeChanged();
      });
    }
    if (chips) fillCountChips(chips, count);

    weightMode.addEventListener("change", () => {
      customWrap.classList.toggle("hidden", weightMode.value !== "custom");
      recalc();
    });

    ["change", "input"].forEach((ev) => {
      card.querySelectorAll("input, select").forEach((el) => {
        el.addEventListener(ev, recalc);
      });
    });
  }

  function onSizeChanged() {
    refreshHistorySelects();
    updateHistSizeLabel();
    if (document.getElementById("panel-history").classList.contains("active")) {
      renderHistoryList();
      refreshMap();
    }
  }

  function allFormCards() {
    return document.querySelectorAll(".form-card, .card[data-side]");
  }

  function readCard(card) {
    return {
      sizeId: card.querySelector(".size").value,
      count: card.querySelector(".count").value,
      priceWon: card.querySelector(".price").value,
      weightMode: card.querySelector(".weight-mode").value,
      customG: card.querySelector(".custom-g")?.value,
      excludeShell,
      edibleRatio: Number(document.getElementById("edible-ratio").value) / 100,
    };
  }

  function applyEntryToCard(card, entry) {
    if (!card || !entry) return;
    const size = card.querySelector(".size");
    const picker = card.querySelector(".egg-picker");
    const count = card.querySelector(".count");
    const price = card.querySelector(".price");
    const weightMode = card.querySelector(".weight-mode");
    const customWrap = card.querySelector(".custom-weight");
    const customG = card.querySelector(".custom-g");
    const chips = card.querySelector(".count-chips");

    size.value = entry.sizeId;
    if (picker) refreshPickerVisual(picker, entry.sizeId);
    count.value = entry.count;
    price.value = entry.priceWon;
    weightMode.value = entry.weightMode || "mid";
    if (customG && entry.customG != null) customG.value = entry.customG;
    customWrap.classList.toggle("hidden", weightMode.value !== "custom");
    if (chips) {
      chips.querySelectorAll(".chip").forEach((c) => {
        c.classList.toggle("on", Number(c.dataset.count) === Number(entry.count));
      });
    }
    if (typeof entry.excludeShell === "boolean") {
      excludeShell = entry.excludeShell;
      document.getElementById("edible-ratio").value = Math.round((entry.edibleRatio || 0.89) * 100);
    }
  }

  function basisLabel(r) {
    return r.excludeShell ? "알맹이(껍질 제외)" : "전체(껍질 포함)";
  }

  function renderOpinionBlock(r) {
    const op = opinionVsMarket(r);
    if (!op.ok) {
      return `<div class="opinion op-fair">
        <p class="opinion-detail">${op.error || "시세 비교 불가"}</p>
        <p class="opinion-note">같은 사이즈 참고가가 필요할 수 있어요. 아래에서 시세를 새로고침해 보세요.</p>
      </div>`;
    }
    const toneClass =
      op.tone === "cheap" ? "op-cheap" : op.tone === "expensive" ? "op-exp" : "op-fair";
    const live = op.market.live
      ? `<span class="live-pill">LIVE</span>`
      : `<span class="live-pill muted">참고</span>`;
    return `
      <div class="opinion ${toneClass}">
        <div class="opinion-top">
          <span class="opinion-badge">${op.label}</span>
          ${live}
          <span class="opinion-pct">${op.pctDiff > 0 ? "+" : ""}${Math.round(op.pctDiff)}%</span>
        </div>
        <p class="opinion-detail">${op.detail}</p>
        <p class="opinion-note">비교 기준: <strong>${op.sizeLabel}</strong> 동일 사이즈만</p>
        <ul class="meta opinion-meta">
          <li><span>내 10g당</span><b>${formatWon(op.mine)}</b></li>
          <li><span>${op.sizeLabel} 시세 10g당</span><b>${formatWon(op.market.per10g)}</b></li>
          <li><span>시세 팩가 (${op.market.packCount}개)</span><b>${formatWon(op.market.packPrice)}</b></li>
          <li><span>시세 출처</span><b>${op.market.asOf} · ${op.market.source}</b></li>
        </ul>
      </div>
    `;
  }

  function renderResult(el, r) {
    if (!r.ok) {
      el.innerHTML = `<p class="hint">${r.error || "입력을 확인하세요"}</p>`;
      lastSingleResult = null;
      return;
    }
    lastSingleResult = r;
    const shellLine = r.excludeShell
      ? `<li><span>알맹이 비율</span><b>${Math.round(r.edibleRatio * 100)}% (껍질 약 ${Math.round(r.shellRatio * 100)}%)</b></li>
         <li><span>총 알맹이</span><b>${formatNum(r.totalUsableG, 1)}g</b></li>`
      : `<li><span>기준</span><b>껍질 포함 전체 중량</b></li>
         <li><span>총 중량</span><b>${formatNum(r.totalUsableG, 1)}g</b></li>`;

    el.innerHTML = `
      <div class="hero-price">
        <span class="label">${basisLabel(r)} 10g당</span>
        <strong>${formatWon(r.per10g)}</strong>
      </div>
      <ul class="meta">
        <li><span>사이즈</span><b>${r.sizeLabel} · ${formatNum(r.unitG, 1)}g/개</b></li>
        <li><span>개수 · 가격</span><b>${r.count}개 · ${formatWon(r.priceWon)}</b></li>
        ${shellLine}
        <li><span>개당</span><b>${formatWon(r.perEgg)}</b></li>
        <li><span>1g당</span><b>${formatNum(r.perGram, 2)}원</b></li>
      </ul>
      ${renderOpinionBlock(r)}
    `;
  }

  function renderCompare(el, c) {
    if (!c.ok) {
      el.innerHTML = `<p class="hint">${c.error || "양쪽 입력을 확인하세요"}</p>`;
      return;
    }

    const sizeCheck = sameSizeCheck(c.a, c.b);
    const warnEl = document.getElementById("compare-size-warn");
    if (!sizeCheck.same) {
      warnEl.textContent = sizeCheck.reason;
      warnEl.classList.remove("hidden");
    } else {
      warnEl.classList.add("hidden");
    }

    // 같은 사이즈 강제 옵션: 다르면 시세 의견 생략 + 경고 강조
    const blockCross = sameSizeOnly && !sizeCheck.same;

    const mode = c.a.excludeShell ? "알맹이 기준" : "껍질 포함 기준";
    const badge = blockCross
      ? `<div class="badge tie">사이즈 불일치</div>`
      : c.cheaper === "tie"
        ? `<div class="badge tie">비슷함</div>`
        : `<div class="badge win">승자 · 상품 ${c.cheaper}</div>`;

    const opA = !blockCross && sizeCheck.same ? opinionVsMarket(c.a) : null;
    const opB = !blockCross && sizeCheck.same ? opinionVsMarket(c.b) : null;
    const marketLine = (op, side) =>
      op && op.ok
        ? `<small class="vs-market ${op.tone}">${side} 시세: ${op.label} (${op.pctDiff > 0 ? "+" : ""}${Math.round(op.pctDiff)}%)</small>`
        : blockCross
          ? `<small class="vs-market fair">동일 사이즈로 맞추면 시세 비교</small>`
          : "";

    const msg = blockCross
      ? "같은 사이즈로 맞춘 뒤 비교해 주세요. (설정: 같은 사이즈만 ON)"
      : c.message;

    el.innerHTML = `
      <div style="text-align:center">${badge}</div>
      <p class="compare-mode-tag">${mode}${sizeCheck.same ? ` · ${sizeCheck.sizeLabel}` : ""}</p>
      <p class="compare-msg">${msg}</p>
      <div class="compare-scores">
        <div class="${!blockCross && c.cheaper === "A" ? "win-side" : ""}">
          <span>A 10g당 · ${c.a.sizeLabel}</span>
          <strong>${formatWon(c.a.per10g)}</strong>
          <small>${c.a.count}개</small>
          ${marketLine(opA, "A")}
        </div>
        <div class="${!blockCross && c.cheaper === "B" ? "win-side" : ""}">
          <span>B 10g당 · ${c.b.sizeLabel}</span>
          <strong>${formatWon(c.b.per10g)}</strong>
          <small>${c.b.count}개</small>
          ${marketLine(opB, "B")}
        </div>
      </div>
    `;
  }

  function updateShellUi() {
    document.getElementById("btn-exclude").classList.toggle("active", excludeShell);
    document.getElementById("btn-include").classList.toggle("active", !excludeShell);
    document.getElementById("shell-help").classList.toggle("hidden", !excludeShell);
    document.getElementById("shell-include-note").classList.toggle("hidden", excludeShell);
    const edible = Number(document.getElementById("edible-ratio").value);
    document.getElementById("edible-label").textContent = edible + "%";
    document.getElementById("edible-range-label").textContent = edible + "%";
    document.getElementById("shell-pct-label").textContent = 100 - edible + "%";
  }

  function emptyHint(el, text) {
    el.innerHTML = `<p class="hint">${text}</p>`;
  }

  function setSaveEnabled(on) {
    const btn = document.getElementById("btn-save");
    if (btn) btn.disabled = !on;
  }

  function recalc() {
    updateShellUi();
    updateLiveBanner();
    const singlePanel = document.getElementById("panel-single");
    const comparePanel = document.getElementById("panel-compare");

    if (singlePanel.classList.contains("active")) {
      const card = singlePanel.querySelector(".form-card, .card");
      const r = calculate(readCard(card));
      if (!String(card.querySelector(".price").value).trim()) {
        emptyHint(
          document.getElementById("result-single"),
          "사이즈·가격·개수를 입력하면 결과가 나타나요"
        );
        lastSingleResult = null;
        setSaveEnabled(false);
      } else {
        renderResult(document.getElementById("result-single"), r);
        setSaveEnabled(!!r.ok);
      }
    } else if (comparePanel.classList.contains("active")) {
      const cards = comparePanel.querySelectorAll(".form-card");
      const a = calculate(readCard(cards[0]));
      const b = calculate(readCard(cards[1]));
      const pa = cards[0].querySelector(".price").value;
      const pb = cards[1].querySelector(".price").value;
      if (!String(pa).trim() || !String(pb).trim()) {
        emptyHint(
          document.getElementById("result-compare"),
          "양쪽 가격을 입력하면 비교 결과가 나타나요"
        );
        document.getElementById("compare-size-warn").classList.add("hidden");
      } else {
        renderCompare(document.getElementById("result-compare"), compare(a, b));
      }
    }
  }

  function formatWhen(iso) {
    try {
      const d = new Date(iso);
      return d.toLocaleString("ko-KR", {
        year: "numeric",
        month: "short",
        day: "numeric",
        hour: "2-digit",
        minute: "2-digit",
      });
    } catch {
      return iso;
    }
  }

  function historyOptionLabel(h) {
    const note = h.note ? ` · ${h.note}` : "";
    const loc = h.locationLabel ? ` · ${h.locationLabel}` : "";
    return `${formatWhen(h.savedAt)}${note}${loc} · ${h.sizeLabel} ${h.count}개 ${formatWon(h.priceWon)}`;
  }

  function filteredHistory(forSelect) {
    let list = loadAll();
    const enforce =
      sameSizeOnly ||
      (forSelect && document.getElementById("chk-same-size-only")?.checked);
    if (enforce) {
      const sid = focusSizeId();
      list = list.filter((h) => h.sizeId === sid);
    }
    return list;
  }

  function refreshHistorySelects() {
    const list = filteredHistory(true);
    ["load-history-a", "load-history-b"].forEach((id) => {
      const sel = document.getElementById(id);
      if (!sel) return;
      const cur = sel.value;
      sel.innerHTML =
        `<option value="">선택… (${list.length}건)</option>` +
        list
          .map((h) => `<option value="${h.id}">${escapeHtml(historyOptionLabel(h))}</option>`)
          .join("");
      if (cur && list.some((h) => h.id === cur)) sel.value = cur;
    });
  }

  function escapeHtml(s) {
    return String(s)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function updateHistSizeLabel() {
    const s = getSize(focusSizeId());
    const el = document.getElementById("hist-size-label");
    if (el) el.textContent = `${s.label} 기준`;
  }

  function renderHistoryList() {
    const sameList = document.getElementById("chk-hist-same-size")?.checked;
    let list = loadAll();
    if (sameList) {
      const sid = focusSizeId();
      list = list.filter((h) => h.sizeId === sid);
    }
    const box = document.getElementById("history-list");
    const countEl = document.getElementById("history-count");
    const clearBtn = document.getElementById("btn-clear-history");
    countEl.textContent = String(list.length);
    clearBtn.classList.toggle("hidden", loadAll().length === 0);
    refreshHistorySelects();
    updateHistSizeLabel();

    if (!list.length) {
      box.innerHTML = `<div class="empty-history">표시할 저장이 없어요.<br/>한 상품에서 저장하거나 사이즈 필터를 해제해 보세요.</div>`;
      return;
    }

    box.innerHTML = list
      .map((h) => {
        const coords =
          h.lat != null && h.lng != null
            ? `${Number(h.lat).toFixed(4)}, ${Number(h.lng).toFixed(4)}`
            : "";
        const color = (EggMap.SIZE_COLORS && EggMap.SIZE_COLORS[h.sizeId]) || "#ff9f0a";
        return `
        <article class="history-card" data-id="${h.id}">
          <div class="history-main">
            <div class="history-price">
              <span class="size-dot" style="background:${color}"></span>
              ${formatWon(h.per10g)} <span>/ 10g · ${escapeHtml(h.sizeLabel)}</span>
            </div>
            <div class="history-title">${escapeHtml(h.sizeLabel)} · ${h.count}개 · ${formatWon(h.priceWon)}</div>
            <div class="history-meta">
              <span>🕒 ${escapeHtml(formatWhen(h.savedAt))}</span>
              <span>📍 ${escapeHtml(h.locationLabel || "위치 없음")}</span>
              ${h.note ? `<span>🏷 ${escapeHtml(h.note)}</span>` : ""}
              ${coords ? `<span class="coords">${coords}</span>` : ""}
            </div>
          </div>
          <div class="history-actions">
            <button type="button" class="btn-mini" data-act="to-a">A에 넣기</button>
            <button type="button" class="btn-mini" data-act="to-b">B에 넣기</button>
            <button type="button" class="btn-mini danger" data-act="del">삭제</button>
          </div>
        </article>`;
      })
      .join("");
  }

  async function refreshMap() {
    const status = document.getElementById("map-status");
    const same = document.getElementById("chk-map-same-size")?.checked;
    const sizeFilter = document.getElementById("map-size-filter")?.value || "";
    try {
      const r = await EggMap.renderHistoryMap("history-map", loadAll(), {
        sameSizeOnly: same && !sizeFilter,
        focusSizeId: focusSizeId(),
        sizeId: sizeFilter || undefined,
      });
      status.textContent =
        r.count > 0
          ? `지도에 ${r.count}개 지점 표시 중`
          : "위치가 있는 저장이 없어요. 저장 시 위치 권한을 허용해 주세요.";
    } catch (e) {
      status.textContent = "지도를 불러오지 못했어요: " + (e.message || e);
    }
  }

  function showToast(msg) {
    const t = document.getElementById("toast");
    t.textContent = msg;
    t.classList.remove("hidden");
    clearTimeout(showToast._timer);
    showToast._timer = setTimeout(() => t.classList.add("hidden"), 2800);
  }

  function goTab(name) {
    document.querySelectorAll(".tab").forEach((t) => {
      t.classList.toggle("active", t.dataset.tab === name);
    });
    document.querySelectorAll(".panel").forEach((p) => {
      p.classList.toggle("active", p.id === "panel-" + name);
    });
    if (name === "history") {
      renderHistoryList();
      refreshMap();
    }
    if (name === "compare") refreshHistorySelects();
    recalc();
  }

  async function onSaveClick() {
    const status = document.getElementById("save-status");
    const btn = document.getElementById("btn-save");
    const card = document.querySelector("#panel-single .form-card");
    const r = calculate(readCard(card));
    if (!r.ok) {
      showToast("저장할 계산 결과가 없어요");
      return;
    }

    btn.disabled = true;
    status.textContent = "위치 확인 중…";
    const note = (document.getElementById("save-note").value || "").trim();
    const input = readCard(card);

    try {
      const saved = await saveEntry(
        {
          note,
          sizeId: r.sizeId,
          sizeLabel: r.sizeLabel,
          count: r.count,
          priceWon: r.priceWon,
          weightMode: input.weightMode,
          customG: input.customG,
          excludeShell: r.excludeShell,
          edibleRatio: r.edibleRatio,
          unitG: r.unitG,
          per10g: r.per10g,
          perEgg: r.perEgg,
        },
        { withLocation: true }
      );
      status.textContent = `저장됨 · ${saved.locationLabel || "위치 없음"} · ${formatWhen(saved.savedAt)}`;
      showToast("가격을 저장했어요");
      renderHistoryList();
    } catch (e) {
      status.textContent = "저장 실패 · 다시 시도해 주세요";
      showToast("저장에 실패했어요");
    } finally {
      btn.disabled = false;
      setSaveEnabled(true);
    }
  }

  function loadHistoryIntoCompare(id, side) {
    const entry = get(id);
    if (!entry) return;
    if (sameSizeOnly) {
      const cards = document.querySelectorAll("#panel-compare .form-card");
      const other = side === "B" ? cards[0] : cards[1];
      if (other) {
        const otherSize = other.querySelector(".size").value;
        // A에 넣을 때 B가 비어있으면 OK; 이미 다른 사이즈면 경고
        if (side === "B") {
          const aSize = cards[0].querySelector(".size").value;
          const aPrice = cards[0].querySelector(".price").value;
          if (String(aPrice).trim() && aSize !== entry.sizeId) {
            if (!confirm(`A는 ${getSize(aSize).label}인데 저장은 ${entry.sizeLabel}이에요. 그래도 넣을까요?`)) {
              return;
            }
          }
        }
      }
    }
    goTab("compare");
    const cards = document.querySelectorAll("#panel-compare .form-card");
    const card = side === "B" ? cards[1] : cards[0];
    applyEntryToCard(card, entry);
    // 같은 사이즈 모드: 반대쪽도 사이즈 동기화(가격은 유지)
    if (sameSizeOnly) {
      const other = side === "B" ? cards[0] : cards[1];
      const oSize = other.querySelector(".size");
      const oPicker = other.querySelector(".egg-picker");
      if (oSize.value !== entry.sizeId) {
        oSize.value = entry.sizeId;
        if (oPicker) refreshPickerVisual(oPicker, entry.sizeId);
      }
    }
    updateShellUi();
    recalc();
    showToast(`저장 가격을 상품 ${side}에 넣었어요`);
  }

  /* ── Live market UI (키 불필요 = 공개 피드) ── */
  function updateLiveBanner() {
    const m = loadMarket();
    const title = document.getElementById("live-title");
    const sub = document.getElementById("live-sub");
    const dot = document.getElementById("live-dot");
    const banner = document.getElementById("live-banner");
    const btn = document.getElementById("btn-fetch-live");
    if (!title) return;
    title.textContent = `전국 참고 시세 · ${m.asOf || ""}`;
    const bits = [m.source || "기본 시세", "하루 1회", "키 불필요"];
    if (m.kamis) bits.push("KAMIS");
    if (m.publicFeed) bits.push("공개 피드");

    const already = EggPublicFeed.refreshedToday();
    if (btn) {
      btn.textContent = already ? "오늘 반영됨" : "오늘 시세 받기";
      btn.disabled = already;
      btn.title = EggPublicFeed.nextRefreshHint();
    }

    const stale = EggPublicFeed.isStale(m.asOf);
    if (stale) {
      const days = EggPublicFeed.daysSinceAsOf(m.asOf);
      bits.unshift(`⚠ ${days}일 전 기준 · 참고만`);
      banner?.classList.add("stale");
      dot.classList.remove("on");
      dot.classList.add("warn");
    } else {
      banner?.classList.remove("stale");
      dot.classList.remove("warn");
      if (m.live || m.publicFeed || already) dot.classList.add("on");
      else dot.classList.remove("on");
    }
    bits.push(already ? "오늘 갱신 완료" : "오늘 미갱신");
    sub.textContent = bits.join(" · ");
  }

  /** 일반 사용자: 하루 1회만 네트워크 (키 없음) */
  async function fetchLive(force) {
    const st = document.getElementById("kamis-status");
    if (EggPublicFeed.refreshedToday() && force) {
      st.textContent = EggPublicFeed.nextRefreshHint();
      showToast("오늘은 이미 시세를 반영했어요");
      updateLiveBanner();
      return;
    }
    st.textContent = "시세 확인 중… (하루 1회)";
    showToast("오늘 시세 확인 중…");
    try {
      const r = await EggPublicFeed.fetchPublicFeed({ force: !!force });
      if (!r.ok) {
        st.textContent = r.error || "피드 실패 — 기본 시세 사용";
        showToast("기본 시세로 비교해요");
        updateLiveBanner();
        recalc();
        return;
      }
      st.textContent =
        (r.message || (r.dailySkip ? "오늘 이미 반영" : "갱신")) +
        ` · ${r.asOf} · ${r.source}`;
      showToast(r.message || (r.dailySkip ? "오늘은 이미 반영됨" : "시세 반영"));
      renderMarketForm();
      updateLiveBanner();
      recalc();
    } catch (e) {
      st.textContent = e.message || String(e);
      showToast("기본 시세로 비교해요");
      updateLiveBanner();
      recalc();
    }
  }

  /** 고급: KAMIS 키가 있을 때만 */
  async function fetchKamis() {
    const st = document.getElementById("kamis-status");
    const creds = {
      certKey: document.getElementById("kamis-key").value.trim(),
      certId: document.getElementById("kamis-id").value.trim(),
      useProxy: document.getElementById("kamis-proxy").checked,
    };
    if (!creds.certKey || !creds.certId) {
      st.textContent = "KAMIS는 선택 사항이에요. 키 없이 공개 피드로 충분합니다.";
      showToast("키 없이 시세 비교 가능");
      return;
    }
    EggKamis.saveCreds(creds);
    st.textContent = "KAMIS 조회 중…";
    try {
      const r = await EggKamis.getLiveOrCache({ ...creds, force: true });
      if (!r.ok) {
        st.textContent = r.error || "KAMIS 실패 — 공개 피드 유지";
        showToast("KAMIS 실패, 공개 시세 유지");
        return;
      }
      st.textContent = `KAMIS 반영 · ${r.asOf}`;
      showToast("KAMIS 시세 반영");
      renderMarketForm();
      updateLiveBanner();
      recalc();
    } catch (e) {
      st.textContent = e.message || String(e);
    }
  }

  function renderMarketForm() {
    const m = EggMarket.loadManual();
    const live = loadMarket();
    document.getElementById("market-asof").value = m.asOf || "";
    document.getElementById("market-source").value = m.source || "";
    const box = document.getElementById("market-price-fields");
    box.innerHTML = SIZES.map((s) => {
      const liveP = live.packPrice[s.id];
      const manP = m.packPrice[s.id];
      return `
      <label class="field compact market-price-row">
        <span class="field-label">${s.label} 30개 ${
          live.live && liveP ? `<em class="live-inline">live ${formatWon(liveP)}</em>` : ""
        }</span>
        <div class="input-wrap">
          <input class="ios-input market-pack" data-size="${s.id}" type="number" min="0" step="100"
            value="${manP ?? liveP ?? ""}" inputmode="numeric" />
          <span class="suffix">원</span>
        </div>
      </label>`;
    }).join("");

    const creds = EggKamis.loadCreds();
    document.getElementById("kamis-key").value = creds.certKey || "";
    document.getElementById("kamis-id").value = creds.certId || "";
    document.getElementById("kamis-proxy").checked = creds.useProxy !== false;
  }

  function onSaveMarket() {
    const packPrice = {};
    document.querySelectorAll(".market-pack").forEach((el) => {
      packPrice[el.dataset.size] = Number(el.value) || 0;
    });
    const market = {
      asOf: document.getElementById("market-asof").value.trim() || "직접 설정",
      source: document.getElementById("market-source").value.trim() || "사용자 입력",
      packCount: 30,
      packPrice,
      live: false,
    };
    // 수동 저장 시 live 캐시는 유지하되 loadMarket이 캐시 우선 — 수동만 쓰려면 캐시 클리어 옵션
    saveMarket(market);
    document.getElementById("market-save-status").textContent =
      "수동 참고가를 저장했어요 (실시간 캐시가 있으면 그쪽이 우선 병합됩니다)";
    showToast("시세 설정 저장");
    updateLiveBanner();
    recalc();
  }

  function initMapSizeFilter() {
    const sel = document.getElementById("map-size-filter");
    sel.innerHTML =
      `<option value="">전체 사이즈</option>` +
      SIZES.map((s) => `<option value="${s.id}">${s.label}만</option>`).join("");
    const legend = document.getElementById("map-legend");
    legend.innerHTML = SIZES.map(
      (s) =>
        `<span><i style="background:${EggMap.SIZE_COLORS[s.id]}"></i>${s.label}</span>`
    ).join("");
  }

  /* ── events ── */
  document.querySelectorAll(".shell-mode .seg-btn").forEach((btn) => {
    btn.addEventListener("click", () => {
      excludeShell = btn.dataset.exclude === "true";
      recalc();
    });
  });

  document.querySelectorAll(".tab").forEach((tab) => {
    tab.addEventListener("click", () => goTab(tab.dataset.tab));
  });

  document.getElementById("btn-save").addEventListener("click", onSaveClick);

  document.getElementById("chk-same-size-only").addEventListener("change", (e) => {
    sameSizeOnly = e.target.checked;
    refreshHistorySelects();
    recalc();
  });

  document.getElementById("chk-hist-same-size").addEventListener("change", renderHistoryList);
  document.getElementById("chk-map-same-size").addEventListener("change", refreshMap);
  document.getElementById("map-size-filter").addEventListener("change", () => {
    if (document.getElementById("map-size-filter").value) {
      document.getElementById("chk-map-same-size").checked = false;
    }
    refreshMap();
  });

  document.getElementById("load-history-a").addEventListener("change", (e) => {
    if (e.target.value) loadHistoryIntoCompare(e.target.value, "A");
  });
  document.getElementById("load-history-b").addEventListener("change", (e) => {
    if (e.target.value) loadHistoryIntoCompare(e.target.value, "B");
  });

  document.getElementById("history-list").addEventListener("click", (e) => {
    const btn = e.target.closest("button[data-act]");
    if (!btn) return;
    const card = btn.closest(".history-card");
    const id = card && card.dataset.id;
    if (!id) return;
    const act = btn.dataset.act;
    if (act === "del") {
      remove(id);
      renderHistoryList();
      refreshMap();
      showToast("삭제했어요");
    } else if (act === "to-a") {
      loadHistoryIntoCompare(id, "A");
    } else if (act === "to-b") {
      loadHistoryIntoCompare(id, "B");
    }
  });

  document.getElementById("btn-clear-history").addEventListener("click", () => {
    if (confirm("저장된 가격을 모두 삭제할까요?")) {
      clear();
      renderHistoryList();
      refreshMap();
      showToast("이력을 모두 삭제했어요");
    }
  });

  document.getElementById("btn-save-market").addEventListener("click", onSaveMarket);
  document.getElementById("btn-reset-market").addEventListener("click", () => {
    resetMarket();
    renderMarketForm();
    document.getElementById("market-save-status").textContent = "기본 시세로 되돌렸어요";
    updateLiveBanner();
    recalc();
  });

  document.getElementById("btn-save-kamis").addEventListener("click", () => {
    EggKamis.saveCreds({
      certKey: document.getElementById("kamis-key").value,
      certId: document.getElementById("kamis-id").value,
      useProxy: document.getElementById("kamis-proxy").checked,
    });
    document.getElementById("kamis-status").textContent = "키를 저장했어요 (선택 사항)";
    showToast("KAMIS 키 저장");
  });

  document.getElementById("btn-fetch-live").addEventListener("click", () => fetchLive(true));
  document.getElementById("btn-fetch-live-2").addEventListener("click", () => fetchLive(true));
  const btnKamis = document.getElementById("btn-fetch-kamis");
  if (btnKamis) btnKamis.addEventListener("click", fetchKamis);

  allFormCards().forEach(wireCard);
  document.getElementById("edible-ratio").addEventListener("input", recalc);

  sameSizeOnly = document.getElementById("chk-same-size-only").checked;
  initMapSizeFilter();
  renderMarketForm();
  renderHistoryList();
  updateLiveBanner();
  recalc();

  // 시작 시 공개 피드 자동 로드 (키 불필요)
  EggPublicFeed.fetchPublicFeed({ force: false }).then(() => {
    updateLiveBanner();
    renderMarketForm();
    recalc();
  });
})();
