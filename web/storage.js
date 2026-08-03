/**
 * 가격 이력 저장 (기기 로컬)
 * - 시간, 위치(선택), 사이즈·가격·10g당 단가
 */
(function (global) {
  const KEY = "egg-price-history-v1";
  const MAX = 50;

  function loadAll() {
    try {
      const raw = localStorage.getItem(KEY);
      if (!raw) return [];
      const list = JSON.parse(raw);
      return Array.isArray(list) ? list : [];
    } catch {
      return [];
    }
  }

  function persist(list) {
    localStorage.setItem(KEY, JSON.stringify(list.slice(0, MAX)));
  }

  function uid() {
    return "h_" + Date.now().toString(36) + "_" + Math.random().toString(36).slice(2, 8);
  }

  function getPosition(timeoutMs) {
    return new Promise((resolve) => {
      if (!navigator.geolocation) {
        resolve(null);
        return;
      }
      navigator.geolocation.getCurrentPosition(
        (pos) =>
          resolve({
            lat: pos.coords.latitude,
            lng: pos.coords.longitude,
            accuracy: pos.coords.accuracy,
          }),
        () => resolve(null),
        { enableHighAccuracy: false, timeout: timeoutMs || 8000, maximumAge: 60000 }
      );
    });
  }

  async function reverseGeocode(lat, lng) {
    try {
      const url =
        "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=" +
        encodeURIComponent(lat) +
        "&longitude=" +
        encodeURIComponent(lng) +
        "&localityLanguage=ko";
      const res = await fetch(url);
      if (!res.ok) return null;
      const data = await res.json();
      const parts = [
        data.principalSubdivision,
        data.city || data.locality,
        data.localityInfo?.administrative?.[0]?.name,
      ].filter(Boolean);
      // 중복 제거
      const uniq = [...new Set(parts)];
      return uniq.slice(0, 2).join(" ") || null;
    } catch {
      return null;
    }
  }

  /**
   * @param {object} entry partial without id/savedAt
   * @param {{ withLocation?: boolean }} opts
   */
  async function saveEntry(entry, opts) {
    const withLocation = !opts || opts.withLocation !== false;
    let lat = null;
    let lng = null;
    let accuracy = null;
    let locationLabel = entry.locationLabel || null;

    if (withLocation) {
      const pos = await getPosition(8000);
      if (pos) {
        lat = pos.lat;
        lng = pos.lng;
        accuracy = pos.accuracy;
        if (!locationLabel) {
          locationLabel = (await reverseGeocode(lat, lng)) || "위치 확인됨";
        }
      } else {
        locationLabel = locationLabel || "위치 없음";
      }
    }

    const full = {
      id: uid(),
      savedAt: new Date().toISOString(),
      lat,
      lng,
      accuracy,
      locationLabel,
      note: entry.note || "",
      sizeId: entry.sizeId,
      sizeLabel: entry.sizeLabel,
      count: entry.count,
      priceWon: entry.priceWon,
      weightMode: entry.weightMode || "mid",
      customG: entry.customG ?? null,
      excludeShell: entry.excludeShell !== false,
      edibleRatio: entry.edibleRatio != null ? entry.edibleRatio : 0.89,
      unitG: entry.unitG,
      per10g: entry.per10g,
      perEgg: entry.perEgg,
    };

    const list = loadAll();
    list.unshift(full);
    persist(list);
    return full;
  }

  function remove(id) {
    persist(loadAll().filter((x) => x.id !== id));
  }

  function clear() {
    localStorage.removeItem(KEY);
  }

  function get(id) {
    return loadAll().find((x) => x.id === id) || null;
  }

  global.EggStorage = {
    loadAll,
    saveEntry,
    remove,
    clear,
    get,
    getPosition,
  };
})(typeof window !== "undefined" ? window : globalThis);
