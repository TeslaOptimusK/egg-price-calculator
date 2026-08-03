/**
 * KAMIS(aT) 실시간 계란 시세 연동
 * - 인증키: KAMIS OpenAPI (p_cert_key + p_cert_id)
 * - 브라우저 CORS 우회: 선택적 프록시 (기본 allorigins)
 * - 성공 시 localStorage 캐시 (6시간)
 *
 * 발급: https://www.kamis.or.kr/customer/reference/openapi_list.do
 */
(function (global) {
  const KEY_CREDS = "egg-kamis-creds-v1";
  const KEY_CACHE = "egg-kamis-cache-v1";
  /** 하루 1회 정책과 맞춤 (고급 KAMIS도 과도 호출 방지) */
  const CACHE_MS = 24 * 60 * 60 * 1000;

  /** 품종명 → 앱 사이즈 id */
  const NAME_TO_SIZE = [
    { re: /왕\s*란|왕란/, id: "wang" },
    { re: /특\s*란|특란/, id: "teuk" },
    { re: /대\s*란|대란/, id: "dae" },
    { re: /중\s*란|중란/, id: "jung" },
    { re: /소\s*란|소란/, id: "so" },
  ];

  function loadCreds() {
    try {
      const raw = localStorage.getItem(KEY_CREDS);
      if (!raw) return { certKey: "", certId: "", useProxy: true };
      return { certKey: "", certId: "", useProxy: true, ...JSON.parse(raw) };
    } catch {
      return { certKey: "", certId: "", useProxy: true };
    }
  }

  function saveCreds(creds) {
    localStorage.setItem(
      KEY_CREDS,
      JSON.stringify({
        certKey: (creds.certKey || "").trim(),
        certId: (creds.certId || "").trim(),
        useProxy: creds.useProxy !== false,
      })
    );
  }

  function loadCache() {
    try {
      const raw = localStorage.getItem(KEY_CACHE);
      if (!raw) return null;
      const c = JSON.parse(raw);
      if (!c || !c.fetchedAt) return null;
      if (Date.now() - c.fetchedAt > CACHE_MS) return null;
      return c;
    } catch {
      return null;
    }
  }

  function saveCache(payload) {
    localStorage.setItem(KEY_CACHE, JSON.stringify(payload));
  }

  function clearCache() {
    localStorage.removeItem(KEY_CACHE);
  }

  function ymd(d) {
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, "0");
    const day = String(d.getDate()).padStart(2, "0");
    return `${y}-${m}-${day}`;
  }

  function buildKamisUrl(creds, action, extra) {
    const u = new URL("https://www.kamis.or.kr/service/price/xml.do");
    u.searchParams.set("action", action);
    u.searchParams.set("p_cert_key", creds.certKey);
    u.searchParams.set("p_cert_id", creds.certId);
    u.searchParams.set("p_returntype", "json");
    Object.entries(extra || {}).forEach(([k, v]) => {
      if (v != null && v !== "") u.searchParams.set(k, v);
    });
    return u.toString();
  }

  async function fetchJson(url, useProxy) {
    let target = url;
    if (useProxy) {
      // CORS 우회 (공개 프록시 — 실패 시 직접 재시도)
      target = "https://api.allorigins.win/raw?url=" + encodeURIComponent(url);
    }
    const res = await fetch(target, { method: "GET" });
    if (!res.ok) throw new Error("HTTP " + res.status);
    const text = await res.text();
    try {
      return JSON.parse(text);
    } catch {
      // 일부 응답이 JSONP/문자열 래핑
      const m = text.match(/\{[\s\S]*\}/);
      if (m) return JSON.parse(m[0]);
      throw new Error("JSON 파싱 실패");
    }
  }

  function asArray(data) {
    if (!data) return [];
    if (Array.isArray(data)) return data;
    if (Array.isArray(data.item)) return data.item;
    if (data.item) return [data.item];
    if (data.data && Array.isArray(data.data)) return data.data;
    if (data.price && Array.isArray(data.price)) return data.price;
    return [];
  }

  function parsePrice(v) {
    if (v == null) return null;
    const s = String(v).replace(/,/g, "").replace(/원/g, "").trim();
    if (!s || s === "-" || s === "0") return null;
    const n = Number(s);
    return Number.isFinite(n) && n > 0 ? n : null;
  }

  /** 단위 문자열 → 개수 (30개 팩 환산용) */
  function unitCount(unit, itemName) {
    const t = `${unit || ""} ${itemName || ""}`;
    const m30 = t.match(/30\s*개|1\s*판|한\s*판|30구|30\s*입/);
    if (m30) return 30;
    const m10 = t.match(/10\s*개|10구|10\s*입/);
    if (m10) return 10;
    const m15 = t.match(/15\s*개|15구/);
    if (m15) return 15;
    const m20 = t.match(/20\s*개|20구/);
    if (m20) return 20;
    const m1 = t.match(/1\s*개|개당/);
    if (m1) return 1;
    // KAMIS 계란 소매 기본이 10개 단위인 경우 많음
    if (/계란|란/.test(t)) return 10;
    return 10;
  }

  function matchSizeId(name) {
    const n = name || "";
    for (const row of NAME_TO_SIZE) {
      if (row.re.test(n)) return row.id;
    }
    // 일반 '계란'만 있으면 특란으로 간주하지 않음
    return null;
  }

  /**
   * 응답 행 → { sizeId, packPrice30, rawPrice, unitCount, name, date }
   */
  function normalizeRow(row) {
    const name =
      row.item_name ||
      row.itemName ||
      row.productName ||
      row.kindName ||
      row.kind_name ||
      row.PUM_NM ||
      "";
    const sizeId = matchSizeId(name);
    if (!sizeId) return null;

    const price =
      parsePrice(row.dpr1) ||
      parsePrice(row.dpr2) ||
      parsePrice(row.price) ||
      parsePrice(row.avg_price) ||
      parsePrice(row.sale_price) ||
      parsePrice(row.sprice) ||
      parsePrice(row.mid);

    if (price == null) return null;

    const unit = row.unit || row.std || row.unit_name || row.se || "";
    const cnt = unitCount(unit, name);
    const packPrice30 = (price / cnt) * 30;

    return {
      sizeId,
      name: String(name),
      rawPrice: price,
      unitCount: cnt,
      packPrice30: Math.round(packPrice30),
      date: row.regday || row.day || row.YYYYMMDD || row.date || "",
      county: row.county_name || row.countyname || row.market_name || "",
    };
  }

  /**
   * 여러 행 중 사이즈별 중앙값(또는 평균) 팩가
   */
  function aggregateBySize(rows) {
    const buckets = { so: [], jung: [], dae: [], teuk: [], wang: [] };
    rows.forEach((r) => {
      if (r && buckets[r.sizeId]) buckets[r.sizeId].push(r.packPrice30);
    });
    const packPrice = {};
    Object.keys(buckets).forEach((id) => {
      const arr = buckets[id].filter((n) => n > 0).sort((a, b) => a - b);
      if (!arr.length) return;
      const mid = arr[Math.floor(arr.length / 2)];
      packPrice[id] = mid;
    });
    return packPrice;
  }

  /**
   * dailyPriceByCategoryList (축산물 500) 우선, 실패 시 periodProductList 보조
   */
  async function fetchLiveMarket(opts) {
    const creds = { ...loadCreds(), ...(opts || {}) };
    if (!creds.certKey || !creds.certId) {
      return {
        ok: false,
        error:
          "KAMIS 인증키·아이디를 입력하세요. (kamis.or.kr OpenAPI 발급)",
      };
    }

    const end = new Date();
    const start = new Date();
    start.setDate(end.getDate() - 3);

    const attempts = [
      {
        action: "dailyPriceByCategoryList",
        extra: {
          p_product_cls_code: "01", // 소매
          p_item_category_code: "500", // 축산물
          p_country_code: "1101", // 서울 (전국 평균 대용, 응답에 따라 무시될 수 있음)
          p_regday: ymd(end).replace(/-/g, ""),
        },
      },
      {
        action: "dailyPriceByCategoryList",
        extra: {
          p_product_cls_code: "01",
          p_item_category_code: "500",
        },
      },
      {
        action: "periodProductList",
        extra: {
          p_startday: ymd(start),
          p_endday: ymd(end),
          p_productclscode: "01",
          p_itemcategorycode: "500",
          p_itemcode: "411",
          p_kindcode: "",
          p_productrankcode: "04",
          p_convert_kg_yn: "N",
        },
      },
    ];

    let lastErr = null;
    let allNorm = [];

    for (const att of attempts) {
      try {
        const url = buildKamisUrl(creds, att.action, att.extra);
        let data;
        try {
          data = await fetchJson(url, creds.useProxy !== false);
        } catch (e1) {
          // 프록시 실패 시 직접
          if (creds.useProxy !== false) {
            data = await fetchJson(url, false);
          } else {
            throw e1;
          }
        }

        const code =
          data?.error_code ||
          data?.result_code ||
          data?.price?.[0]?.error_code ||
          "";
        if (String(code) === "001" || /인증|key|권한/i.test(JSON.stringify(data).slice(0, 200))) {
          // 일부 API는 성공도 000
        }
        if (/^[2-9]/.test(String(code)) && String(code) !== "000" && String(code) !== "001") {
          lastErr = "KAMIS 오류 코드 " + code;
          continue;
        }

        const items = [
          ...asArray(data),
          ...asArray(data?.data),
          ...asArray(data?.price),
          ...asArray(data?.item),
        ];
        // 중첩 구조
        if (data?.data?.item) items.push(...asArray(data.data.item));

        const norm = items.map(normalizeRow).filter(Boolean);
        if (norm.length) {
          allNorm = allNorm.concat(norm);
          break;
        }
        lastErr = "계란 사이즈 항목을 응답에서 찾지 못함";
      } catch (e) {
        lastErr = e.message || String(e);
      }
    }

    if (!allNorm.length) {
      return {
        ok: false,
        error:
          lastErr ||
          "시세를 가져오지 못했어요. 키·네트워크·CORS 프록시를 확인하세요.",
      };
    }

    const packPrice = aggregateBySize(allNorm);
    if (!Object.keys(packPrice).length) {
      return { ok: false, error: "사이즈별 가격 집계 실패" };
    }

    // 빠진 사이즈는 이전 캐시/기본으로 채우지 않음 — 있는 것만
    const asOf = ymd(end);
    const sample = allNorm[0];
    const payload = {
      ok: true,
      asOf,
      source: "KAMIS 소매(축산물) 실시간",
      packCount: 30,
      packPrice,
      fetchedAt: Date.now(),
      sampleCount: allNorm.length,
      sampleName: sample?.name || "",
      live: true,
    };
    saveCache(payload);
    return payload;
  }

  /**
   * 캐시 우선, 없거나 force 시 네트워크
   */
  async function getLiveOrCache(opts) {
    const force = opts && opts.force;
    if (!force) {
      const c = loadCache();
      if (c && c.packPrice) return { ...c, ok: true, fromCache: true };
    }
    return fetchLiveMarket(opts);
  }

  global.EggKamis = {
    loadCreds,
    saveCreds,
    loadCache,
    clearCache,
    fetchLiveMarket,
    getLiveOrCache,
    matchSizeId,
    NAME_TO_SIZE,
  };
})(typeof window !== "undefined" ? window : globalThis);
