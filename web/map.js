/**
 * 저장 지점 지도 (Leaflet + OSM)
 * - 위·경도 있는 이력만 표시
 * - 사이즈 필터 / 동일 사이즈만
 */
(function (global) {
  let map = null;
  let layerGroup = null;
  let leafletReady = null;

  const SIZE_COLORS = {
    so: "#8e8e93",
    jung: "#64d2ff",
    dae: "#30d158",
    teuk: "#ff9f0a",
    wang: "#ff375f",
  };

  function loadLeaflet() {
    if (global.L) return Promise.resolve(global.L);
    if (leafletReady) return leafletReady;
    leafletReady = new Promise((resolve, reject) => {
      const css = document.createElement("link");
      css.rel = "stylesheet";
      css.href = "https://unpkg.com/leaflet@1.9.4/dist/leaflet.css";
      document.head.appendChild(css);
      const s = document.createElement("script");
      s.src = "https://unpkg.com/leaflet@1.9.4/dist/leaflet.js";
      s.onload = () => resolve(global.L);
      s.onerror = () => reject(new Error("Leaflet 로드 실패"));
      document.head.appendChild(s);
    });
    return leafletReady;
  }

  function circleIcon(L, color) {
    return L.divIcon({
      className: "egg-map-pin",
      html: `<span style="
        display:block;width:16px;height:16px;border-radius:50%;
        background:${color};border:2px solid #fff;
        box-shadow:0 1px 4px rgba(0,0,0,.35)"></span>`,
      iconSize: [16, 16],
      iconAnchor: [8, 8],
    });
  }

  async function ensureMap(containerId) {
    const L = await loadLeaflet();
    const el = document.getElementById(containerId);
    if (!el) throw new Error("지도 컨테이너 없음");
    if (map) {
      setTimeout(() => map.invalidateSize(), 80);
      return map;
    }
    map = L.map(el, { zoomControl: true, attributionControl: true }).setView(
      [36.5, 127.8],
      7
    );
    L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
      maxZoom: 19,
      attribution: "&copy; OpenStreetMap",
    }).addTo(map);
    layerGroup = L.layerGroup().addTo(map);
    setTimeout(() => map.invalidateSize(), 100);
    return map;
  }

  /**
   * @param {Array} entries history
   * @param {{ sizeId?: string, sameSizeOnly?: boolean, focusSizeId?: string }} filter
   */
  async function renderHistoryMap(containerId, entries, filter) {
    const L = await loadLeaflet();
    await ensureMap(containerId);
    layerGroup.clearLayers();

    const f = filter || {};
    let list = (entries || []).filter(
      (e) => e.lat != null && e.lng != null && Number.isFinite(e.lat) && Number.isFinite(e.lng)
    );
    if (f.sameSizeOnly && f.focusSizeId) {
      list = list.filter((e) => e.sizeId === f.focusSizeId);
    } else if (f.sizeId) {
      list = list.filter((e) => e.sizeId === f.sizeId);
    }

    const bounds = [];
    list.forEach((e) => {
      const color = SIZE_COLORS[e.sizeId] || "#ff9f0a";
      const m = L.marker([e.lat, e.lng], { icon: circleIcon(L, color) });
      const when = e.savedAt
        ? new Date(e.savedAt).toLocaleString("ko-KR", {
            month: "short",
            day: "numeric",
            hour: "2-digit",
            minute: "2-digit",
          })
        : "";
      const price =
        e.per10g != null
          ? Math.round(e.per10g).toLocaleString("ko-KR") + "원/10g"
          : "";
      m.bindPopup(
        `<strong>${e.note || e.locationLabel || "저장 지점"}</strong><br/>
         ${e.sizeLabel || ""} · ${e.count || ""}개 · ${
          e.priceWon != null ? Math.round(e.priceWon).toLocaleString("ko-KR") + "원" : ""
        }<br/>
         ${price}<br/>
         <span style="color:#8e8e93">${when}</span>`
      );
      m.addTo(layerGroup);
      bounds.push([e.lat, e.lng]);
    });

    if (bounds.length === 1) {
      map.setView(bounds[0], 14);
    } else if (bounds.length > 1) {
      map.fitBounds(bounds, { padding: [36, 36], maxZoom: 14 });
    } else {
      map.setView([36.5, 127.8], 7);
    }

    return { count: list.length, totalWithGeo: list.length };
  }

  function destroy() {
    if (map) {
      map.remove();
      map = null;
      layerGroup = null;
    }
  }

  global.EggMap = {
    ensureMap,
    renderHistoryMap,
    destroy,
    SIZE_COLORS,
  };
})(typeof window !== "undefined" ? window : globalThis);
