/**
 * 계란 단가 계산 로직 (SPEC.md)
 * 껍질 포함 / 제외 옵션 지원
 */
(function (global) {
  // 표시 순서: 소 → 왕 (이미지 피커용)
  const SIZES = [
    { id: "so", label: "소란", midG: 40, minG: 36, scale: 0.72 },
    { id: "jung", label: "중란", midG: 48, minG: 44, scale: 0.82 },
    { id: "dae", label: "대란", midG: 56, minG: 52, scale: 0.92 },
    { id: "teuk", label: "특란", midG: 64, minG: 60, scale: 1.0 },
    { id: "wang", label: "왕란", midG: 70, minG: 68, scale: 1.1 },
  ];

  const COUNT_PRESETS = [10, 15, 20, 30];
  const DEFAULT_EDIBLE_RATIO = 0.89; // 껍질 약 11% 가정

  function getSize(id) {
    return SIZES.find((s) => s.id === id) || SIZES[3];
  }

  function resolveUnitGrams(sizeId, weightMode, customG) {
    if (weightMode === "custom") {
      const g = Number(customG);
      if (!Number.isFinite(g) || g <= 0) return null;
      return g;
    }
    const size = getSize(sizeId);
    return weightMode === "min" ? size.minG : size.midG;
  }

  /**
   * @param {object} p
   * @param {boolean} [p.excludeShell]  true=알맹이만, false=껍질 포함 전체
   * @param {number}  [p.edibleRatio]   껍질 제외 시 알맹이 비율 (기본 0.89)
   */
  function calculate(p) {
    const count = Number(p.count);
    const priceWon = Number(p.priceWon);
    const excludeShell = p.excludeShell !== false; // 기본: 껍질 제외
    const edibleRatio = excludeShell
      ? p.edibleRatio != null
        ? Number(p.edibleRatio)
        : DEFAULT_EDIBLE_RATIO
      : 1;

    if (!Number.isFinite(count) || count < 1) {
      return { ok: false, error: "개수를 확인하세요" };
    }
    if (!Number.isFinite(priceWon) || priceWon < 0) {
      return { ok: false, error: "가격을 확인하세요" };
    }
    if (!Number.isFinite(edibleRatio) || edibleRatio <= 0 || edibleRatio > 1) {
      return { ok: false, error: "알맹이 비율을 확인하세요" };
    }

    const unitG = resolveUnitGrams(p.sizeId, p.weightMode || "mid", p.customG);
    if (unitG == null) {
      return { ok: false, error: "1개 중량을 입력하세요" };
    }

    const usablePerEggG = unitG * edibleRatio;
    const totalUsableG = usablePerEggG * count;
    if (totalUsableG <= 0) {
      return { ok: false, error: "중량이 올바르지 않습니다" };
    }

    const perGram = priceWon / totalUsableG;
    const per10g = perGram * 10;
    const perEgg = priceWon / count;
    const shellRatio = excludeShell ? 1 - edibleRatio : 0;

    return {
      ok: true,
      sizeId: p.sizeId,
      sizeLabel: getSize(p.sizeId).label,
      count,
      priceWon,
      unitG,
      excludeShell,
      edibleRatio,
      shellRatio,
      usablePerEggG,
      totalUsableG,
      // 하위 호환
      ediblePerEggG: usablePerEggG,
      totalEdibleG: totalUsableG,
      perGram,
      per10g,
      perEgg,
    };
  }

  function compare(a, b) {
    if (!a.ok || !b.ok) {
      return { ok: false, error: a.ok ? b.error : a.error };
    }
    const diff10 = a.per10g - b.per10g;
    let cheaper;
    let message;
    if (Math.abs(diff10) < 0.05) {
      cheaper = "tie";
      message = "10g당 단가가 거의 같습니다";
    } else if (diff10 > 0) {
      cheaper = "B";
      message = `상품 B가 10g당 약 ${formatWon(Math.abs(diff10))} 저렴`;
    } else {
      cheaper = "A";
      message = `상품 A가 10g당 약 ${formatWon(Math.abs(diff10))} 저렴`;
    }
    return {
      ok: true,
      a,
      b,
      cheaper,
      diff10g: Math.abs(diff10),
      message,
    };
  }

  function formatWon(n) {
    if (!Number.isFinite(n)) return "-";
    return Math.round(n).toLocaleString("ko-KR") + "원";
  }

  function formatNum(n, digits) {
    if (!Number.isFinite(n)) return "-";
    return n.toLocaleString("ko-KR", {
      maximumFractionDigits: digits,
      minimumFractionDigits: 0,
    });
  }

  /** 사이즈별 계란 SVG (scale로 크기 차별) */
  function eggSvg(scale, selected) {
    const w = Math.round(36 * scale);
    const h = Math.round(46 * scale);
    const stroke = selected ? "#e68600" : "#c7c7cc";
    const fill = selected ? "#fff4e0" : "#ffffff";
    const yolk = selected ? "#ff9f0a" : "#e8c060";
    return `<svg width="${w}" height="${h}" viewBox="0 0 40 52" aria-hidden="true">
      <ellipse cx="20" cy="28" rx="16" ry="21" fill="${fill}" stroke="${stroke}" stroke-width="2"/>
      <ellipse cx="20" cy="30" rx="7" ry="7.5" fill="${yolk}" opacity="0.85"/>
      <ellipse cx="17" cy="18" rx="4" ry="2.5" fill="#fff" opacity="0.45"/>
    </svg>`;
  }

  global.EggCalc = {
    SIZES,
    COUNT_PRESETS,
    DEFAULT_EDIBLE_RATIO,
    getSize,
    resolveUnitGrams,
    calculate,
    compare,
    formatWon,
    formatNum,
    eggSvg,
  };
})(typeof window !== "undefined" ? window : globalThis);
