/**
 * 전국 계란 참고 시세
 * 우선순위 (키 불필요 우선):
 *  1) 공개 시세 피드 캐시 (EggPublicFeed)
 *  2) KAMIS 캐시 (개발자/고급 — 선택)
 *  3) 사용자 수동 팩가
 *  4) 앱 기본값
 * 시세 비교는 항상 동일 사이즈(sizeId)끼리만
 */
(function (global) {
  const STORAGE_KEY = "egg-market-ref-v1";

  const DEFAULT_MARKET = {
    asOf: "2026-08-03",
    source: "2026년 8월 소매 참고 추정치 (키 불필요)",
    packCount: 30,
    packPrice: {
      so: 5200,
      jung: 5900,
      dae: 6600,
      teuk: 7400,
      wang: 8200,
    },
    live: false,
  };

  function cloneDefault() {
    return JSON.parse(JSON.stringify(DEFAULT_MARKET));
  }

  function loadManual() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return cloneDefault();
      const parsed = JSON.parse(raw);
      return {
        ...DEFAULT_MARKET,
        ...parsed,
        packPrice: { ...DEFAULT_MARKET.packPrice, ...(parsed.packPrice || {}) },
        live: !!parsed.live,
      };
    } catch {
      return cloneDefault();
    }
  }

  function loadMarket() {
    try {
      const base = loadManual();
      let pack = { ...base.packPrice };
      let asOf = base.asOf;
      let source = base.source;
      let live = false;
      let meta = {};

      // 공개 피드 (일반 사용자 기본)
      if (global.EggPublicFeed) {
        const pub = global.EggPublicFeed.loadCache();
        if (pub && pub.packPrice && Object.keys(pub.packPrice).length) {
          pack = { ...pack, ...pub.packPrice };
          asOf = pub.asOf || asOf;
          source = pub.source || "공개 시세 피드";
          live = true;
          meta.publicFeed = true;
          meta.fetchedAt = pub.fetchedAt;
        }
      }

      // KAMIS (있으면 덮어씀 — 고급)
      if (global.EggKamis) {
        const kamis = global.EggKamis.loadCache();
        if (kamis && kamis.packPrice && Object.keys(kamis.packPrice).length) {
          pack = { ...pack, ...kamis.packPrice };
          asOf = kamis.asOf || asOf;
          source = kamis.source || "KAMIS 실시간";
          live = true;
          meta.kamis = true;
          meta.sampleCount = kamis.sampleCount;
          meta.fetchedAt = kamis.fetchedAt || meta.fetchedAt;
        }
      }

      return {
        ...base,
        asOf,
        source,
        packCount: 30,
        packPrice: pack,
        live,
        ...meta,
      };
    } catch {
      return cloneDefault();
    }
  }

  function saveMarket(market) {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        asOf: market.asOf,
        source: market.source,
        packCount: market.packCount || 30,
        packPrice: market.packPrice,
        live: !!market.live,
      })
    );
  }

  function resetMarket() {
    localStorage.removeItem(STORAGE_KEY);
    if (global.EggPublicFeed) global.EggPublicFeed.clearCache();
    if (global.EggKamis) global.EggKamis.clearCache();
    return cloneDefault();
  }

  function marketPer10g(sizeId, opts) {
    const EggCalc = global.EggCalc;
    if (!sizeId) return null;
    const market = (opts && opts.market) || loadMarket();
    const packPrice = market.packPrice && market.packPrice[sizeId];
    if (packPrice == null || !(packPrice > 0)) {
      return { ok: false, error: "이 사이즈의 참고 시세가 없어요", sizeId };
    }
    const r = EggCalc.calculate({
      sizeId,
      count: market.packCount || 30,
      priceWon: packPrice,
      weightMode: "mid",
      excludeShell: opts.excludeShell !== false,
      edibleRatio: opts.edibleRatio != null ? opts.edibleRatio : 0.89,
    });
    if (!r.ok) return null;
    return {
      ok: true,
      sizeId,
      packPrice,
      packCount: market.packCount || 30,
      per10g: r.per10g,
      perEgg: r.perEgg,
      sizeLabel: r.sizeLabel,
      asOf: market.asOf,
      source: market.source,
      live: !!market.live,
    };
  }

  function opinionVsMarket(calcResult, opts) {
    if (!calcResult || !calcResult.ok) {
      return { ok: false, error: "계산 결과가 없습니다" };
    }
    const m = marketPer10g(calcResult.sizeId, {
      excludeShell: calcResult.excludeShell,
      edibleRatio: calcResult.edibleRatio,
      market: opts && opts.market,
    });
    if (!m || !m.ok) {
      return {
        ok: false,
        error: (m && m.error) || "동일 사이즈 시세가 없습니다",
        sizeId: calcResult.sizeId,
        sizeOnly: true,
      };
    }
    if (m.sizeId !== calcResult.sizeId) {
      return { ok: false, error: "사이즈 불일치 — 비교 중단", sizeOnly: true };
    }

    const mine = calcResult.per10g;
    const ref = m.per10g;
    const pctDiff = ((mine - ref) / ref) * 100;
    const abs = Math.abs(pctDiff);

    let level;
    let label;
    let tone;
    if (pctDiff <= -15) {
      level = "great";
      label = "매우 저렴";
      tone = "cheap";
    } else if (pctDiff <= -5) {
      level = "good";
      label = "저렴한 편";
      tone = "cheap";
    } else if (pctDiff < 5) {
      level = "fair";
      label = "시세 수준";
      tone = "fair";
    } else if (pctDiff < 15) {
      level = "high";
      label = "조금 비싼 편";
      tone = "expensive";
    } else {
      level = "pricey";
      label = "비싼 편";
      tone = "expensive";
    }

    const dir = pctDiff < 0 ? "저렴" : pctDiff > 0 ? "비쌈" : "동일";
    const liveTag = m.live ? "시세" : "참고";
    const detail =
      pctDiff === 0
        ? `${m.sizeLabel} ${liveTag}와 10g당이 같아요`
        : `${m.sizeLabel} ${liveTag} 대비 10g당 약 ${Math.round(abs)}% ${dir}해요`;

    return {
      ok: true,
      level,
      label,
      tone,
      detail,
      pctDiff,
      mine,
      market: m,
      sizeOnly: true,
      sizeId: calcResult.sizeId,
      sizeLabel: calcResult.sizeLabel || m.sizeLabel,
    };
  }

  function sameSizeCheck(a, b) {
    if (!a || !b || !a.ok || !b.ok) {
      return { same: false, reason: "계산 결과 없음" };
    }
    if (a.sizeId !== b.sizeId) {
      return {
        same: false,
        reason: `사이즈가 달라요 (A ${a.sizeLabel} · B ${b.sizeLabel}). 시세·이력이 왜곡될 수 있어 같은 호수로 맞춰 주세요.`,
        sizeA: a.sizeId,
        sizeB: b.sizeId,
      };
    }
    return { same: true, sizeId: a.sizeId, sizeLabel: a.sizeLabel };
  }

  global.EggMarket = {
    DEFAULT_MARKET,
    loadMarket,
    loadManual,
    saveMarket,
    resetMarket,
    marketPer10g,
    opinionVsMarket,
    sameSizeCheck,
  };
})(typeof window !== "undefined" ? window : globalThis);
