/**
 * 키 없이 쓰는 공개 시세 피드
 * - 기본 정책: **하루 1회** 네트워크 갱신 (서버·부하 절감)
 * - 같은 날 재요청은 로컬 캐시만 사용
 * - force 도 같은 날이면 캐시 유지 (수동 연타 방지)
 */
(function (global) {
  const CACHE_KEY = "egg-public-feed-v3"; // v3: 하루 1회 정책
  /** 이보다 오래된 asOf 면 UI에 경고 */
  const STALE_DAYS = 45;
  const TZ = "Asia/Seoul";

  const LOCAL_FEED = "market-live.json";
  const CONFIG_FEED = "feed-config.json";
  /** feed-config.json 의 remoteFeeds + 여기 기본값 */
  let REMOTE_FEEDS = [];

  async function loadFeedConfig() {
    try {
      const res = await fetch(CONFIG_FEED + "?t=" + Date.now());
      if (!res.ok) return;
      const cfg = await res.json();
      if (Array.isArray(cfg.remoteFeeds)) {
        REMOTE_FEEDS = cfg.remoteFeeds.filter(
          (u) => typeof u === "string" && u.startsWith("http")
        );
      }
    } catch (_) {
      /* file:// 또는 없음 */
    }
  }

  function todayKey() {
    try {
      return new Intl.DateTimeFormat("en-CA", {
        timeZone: TZ,
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
      }).format(new Date()); // YYYY-MM-DD
    } catch {
      return new Date().toISOString().slice(0, 10);
    }
  }

  function loadCache() {
    try {
      const raw = localStorage.getItem(CACHE_KEY);
      if (!raw) return null;
      const c = JSON.parse(raw);
      if (!c || !c.packPrice) return null;
      return c;
    } catch {
      return null;
    }
  }

  function saveCache(payload) {
    const day = todayKey();
    localStorage.setItem(
      CACHE_KEY,
      JSON.stringify({
        ...payload,
        fetchedAt: Date.now(),
        fetchDay: day,
      })
    );
  }

  function clearCache() {
    localStorage.removeItem(CACHE_KEY);
    try {
      localStorage.removeItem("egg-public-feed-v1");
      localStorage.removeItem("egg-public-feed-v2");
    } catch (_) {}
  }

  /** 오늘(한국 날짜) 이미 갱신했는지 */
  function refreshedToday() {
    const c = loadCache();
    if (!c || !c.fetchDay) return false;
    return c.fetchDay === todayKey();
  }

  function nextRefreshHint() {
    if (!refreshedToday()) return "오늘 아직 미갱신 · 자동/버튼으로 1회 받을 수 있어요";
    return "오늘은 이미 반영됨 · 내일 자동 갱신";
  }

  function daysSinceAsOf(asOf) {
    if (!asOf) return null;
    const s = String(asOf).trim();
    let d;
    if (/^\d{4}-\d{2}-\d{2}/.test(s)) d = new Date(s.slice(0, 10) + "T00:00:00");
    else if (/^\d{4}-\d{2}$/.test(s)) d = new Date(s + "-01T00:00:00");
    else return null;
    if (Number.isNaN(d.getTime())) return null;
    return Math.floor((Date.now() - d.getTime()) / (24 * 60 * 60 * 1000));
  }

  function isStale(asOf) {
    const days = daysSinceAsOf(asOf);
    if (days == null) return false;
    return days > STALE_DAYS;
  }

  function normalize(data, sourceHint) {
    if (!data || typeof data !== "object") return null;
    const pack = data.packPrice || data.prices || data.pack_price;
    if (!pack || typeof pack !== "object") return null;
    const packPrice = {};
    ["so", "jung", "dae", "teuk", "wang"].forEach((id) => {
      const n = Number(pack[id]);
      if (Number.isFinite(n) && n > 0) packPrice[id] = Math.round(n);
    });
    if (!Object.keys(packPrice).length) return null;
    return {
      ok: true,
      asOf: data.asOf || data.date || todayKey(),
      source: data.source || sourceHint || "공개 시세 피드",
      packCount: Number(data.packCount) || 30,
      packPrice,
      note: data.note || "",
      live: true,
      publicFeed: true,
      fetchedAt: Date.now(),
      fetchDay: todayKey(),
      dailyLimit: true,
    };
  }

  async function fetchUrl(url) {
    const res = await fetch(url, { cache: "no-store" });
    if (!res.ok) throw new Error("HTTP " + res.status);
    return res.json();
  }

  /**
   * @param {{ force?: boolean, bypassDaily?: boolean }} opts
   * - force: 사용자 버튼 (같은 날이면 캐시만, 네트워크 X)
   * - bypassDaily: 개발/테스트용 강제 네트워크 (기본 false)
   */
  async function fetchPublicFeed(opts) {
    const force = opts && opts.force;
    const bypass = opts && opts.bypassDaily;

    const cached = loadCache();

    // 하루 1회: 오늘 캐시 있으면 네트워크 스킵
    if (!bypass && cached && cached.packPrice && refreshedToday()) {
      return {
        ...cached,
        ok: true,
        fromCache: true,
        dailySkip: true,
        message: "오늘은 이미 시세를 반영했어요. 내일 다시 갱신돼요.",
      };
    }

    await loadFeedConfig();

    // 오늘 미갱신 → 원격(자동화 피드) 우선, 그다음 로컬
    const tried = [];
    for (const url of REMOTE_FEEDS) {
      try {
        const data = await fetchUrl(url);
        const n = normalize(data, "원격 공개 시세");
        if (n) {
          saveCache(n);
          return { ...n, fromCache: false, message: "오늘 시세를 반영했어요 (하루 1회)" };
        }
      } catch (e) {
        tried.push(url + ": " + (e.message || e));
      }
    }

    try {
      const data = await fetchUrl(LOCAL_FEED + "?t=" + Date.now());
      const n = normalize(data, "앱 내장 시세 피드");
      if (n) {
        saveCache(n);
        return {
          ...n,
          fromCache: false,
          message: force
            ? "오늘 시세를 반영했어요 (하루 1회)"
            : "시세 준비됨 (하루 1회 갱신)",
        };
      }
    } catch (e) {
      tried.push("local: " + (e.message || e));
    }

    if (cached && cached.packPrice) {
      return {
        ...cached,
        ok: true,
        fromCache: true,
        stale: true,
        source: (cached.source || "캐시") + " (오프라인)",
        message: "네트워크 없이 저장된 시세를 써요",
      };
    }

    const fallback = {
      ok: true,
      asOf: "2026-08-03",
      source: "앱 기본 참고 시세 (키 불필요)",
      packCount: 30,
      packPrice: {
        so: 5200,
        jung: 5900,
        dae: 6600,
        teuk: 7400,
        wang: 8200,
      },
      live: true,
      publicFeed: true,
      bundled: true,
      fetchedAt: Date.now(),
      fetchDay: todayKey(),
      dailyLimit: true,
      message: "기본 시세 적용 (하루 1회 정책)",
    };
    saveCache(fallback);
    return fallback;
  }

  global.EggPublicFeed = {
    loadCache,
    clearCache,
    fetchPublicFeed,
    refreshedToday,
    nextRefreshHint,
    todayKey,
    daysSinceAsOf,
    isStale,
    STALE_DAYS,
    LOCAL_FEED,
    REMOTE_FEEDS,
  };
})(typeof window !== "undefined" ? window : globalThis);
