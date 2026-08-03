/**
 * 2단계 공개 시세 피드 자동 갱신 (서버 구축 없이)
 *
 * 실행:
 *   node scripts/update-market-feed.mjs
 *
 * 환경변수 (선택 — 있으면 KAMIS 실데이터):
 *   KAMIS_CERT_KEY, KAMIS_CERT_ID
 *
 * 없으면: 기존 packPrice 유지 + asOf 오늘로 갱신 (파이프라인 검증용)
 * GitHub Actions 시크릿에 키를 넣으면 매일 실시세 반영 가능.
 */
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, "..");
const WEB_JSON = path.join(ROOT, "web", "market-live.json");
const ANDROID_JSON = path.join(
  ROOT,
  "android",
  "app",
  "src",
  "main",
  "assets",
  "market-live.json"
);

const SIZES = ["so", "jung", "dae", "teuk", "wang"];
const NAME_RULES = [
  { re: /왕\s*란|왕란/, id: "wang" },
  { re: /특\s*란|특란/, id: "teuk" },
  { re: /대\s*란|대란/, id: "dae" },
  { re: /중\s*란|중란/, id: "jung" },
  { re: /소\s*란|소란/, id: "so" },
];

function todayKst() {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date());
}

function readExisting() {
  try {
    return JSON.parse(fs.readFileSync(WEB_JSON, "utf8"));
  } catch {
    return {
      packCount: 30,
      packPrice: {
        so: 5200,
        jung: 5900,
        dae: 6600,
        teuk: 7400,
        wang: 8200,
      },
    };
  }
}

function parsePrice(v) {
  if (v == null) return null;
  const s = String(v).replace(/,/g, "").replace(/원/g, "").trim();
  if (!s || s === "-" || s === "0") return null;
  const n = Number(s);
  return Number.isFinite(n) && n > 0 ? n : null;
}

function unitCount(unit, name) {
  const t = `${unit || ""} ${name || ""}`;
  if (/30\s*개|1\s*판|한\s*판|30구/.test(t)) return 30;
  if (/15\s*개|15구/.test(t)) return 15;
  if (/20\s*개|20구/.test(t)) return 20;
  if (/10\s*개|10구/.test(t)) return 10;
  if (/1\s*개|개당/.test(t)) return 1;
  return 10;
}

function matchSize(name) {
  for (const r of NAME_RULES) {
    if (r.re.test(name || "")) return r.id;
  }
  return null;
}

function walkItems(node, out = []) {
  if (!node) return out;
  if (Array.isArray(node)) {
    node.forEach((x) => walkItems(x, out));
    return out;
  }
  if (typeof node === "object") {
    if (node.item_name || node.itemName || node.dpr1 != null) out.push(node);
    Object.values(node).forEach((v) => walkItems(v, out));
  }
  return out;
}

async function fetchKamis(certKey, certId) {
  const ymd = todayKst().replace(/-/g, "");
  const params = new URLSearchParams({
    action: "dailyPriceByCategoryList",
    p_cert_key: certKey,
    p_cert_id: certId,
    p_returntype: "json",
    p_product_cls_code: "01",
    p_item_category_code: "500",
    p_regday: ymd,
  });
  const url = `https://www.kamis.or.kr/service/price/xml.do?${params}`;
  const res = await fetch(url);
  if (!res.ok) throw new Error(`KAMIS HTTP ${res.status}`);
  const text = await res.text();
  let data;
  try {
    data = JSON.parse(text);
  } catch {
    const m = text.match(/\{[\s\S]*\}/);
    if (!m) throw new Error("KAMIS JSON 파싱 실패");
    data = JSON.parse(m[0]);
  }

  const items = walkItems(data);
  const buckets = { so: [], jung: [], dae: [], teuk: [], wang: [] };

  for (const row of items) {
    const name =
      row.item_name || row.itemName || row.kind_name || row.kindName || "";
    const sizeId = matchSize(name);
    if (!sizeId) continue;
    const price =
      parsePrice(row.dpr1) ||
      parsePrice(row.dpr2) ||
      parsePrice(row.price) ||
      parsePrice(row.avg_price);
    if (price == null) continue;
    const unit = row.unit || row.unit_name || "";
    const cnt = unitCount(unit, name);
    buckets[sizeId].push((price / cnt) * 30);
  }

  const packPrice = {};
  for (const id of SIZES) {
    const arr = buckets[id].filter((n) => n > 0).sort((a, b) => a - b);
    if (arr.length) packPrice[id] = Math.round(arr[Math.floor(arr.length / 2)]);
  }
  if (!Object.keys(packPrice).length) {
    throw new Error("KAMIS 응답에서 계란 호수를 찾지 못함");
  }
  return {
    packPrice,
    sampleCount: Object.values(buckets).reduce((a, b) => a + b.length, 0),
  };
}

function writeFeed(feed) {
  const text = JSON.stringify(feed, null, 2) + "\n";
  fs.mkdirSync(path.dirname(WEB_JSON), { recursive: true });
  fs.mkdirSync(path.dirname(ANDROID_JSON), { recursive: true });
  fs.writeFileSync(WEB_JSON, text, "utf8");
  fs.writeFileSync(ANDROID_JSON, text, "utf8");
  console.log("Wrote", WEB_JSON);
  console.log("Wrote", ANDROID_JSON);
}

async function main() {
  const existing = readExisting();
  const today = todayKst();
  const key = process.env.KAMIS_CERT_KEY || process.env.KAMIS_KEY || "";
  const id = process.env.KAMIS_CERT_ID || process.env.KAMIS_ID || "";

  let packPrice = { ...(existing.packPrice || {}) };
  let source = existing.source || "앱 공개 시세 피드";
  let note = existing.note || "";
  let mode = "date-roll";

  if (key && id) {
    try {
      const r = await fetchKamis(key, id);
      packPrice = { ...packPrice, ...r.packPrice };
      source = `KAMIS 소매 자동갱신 (${today})`;
      note = `GitHub Actions가 하루 1회 KAMIS를 조회해 갱신했습니다. 샘플 ${r.sampleCount}건. 공식 공시와 단위·지역 차이가 있을 수 있습니다.`;
      mode = "kamis";
      console.log("KAMIS ok", packPrice);
    } catch (e) {
      console.warn("KAMIS failed, keep previous prices:", e.message);
      source = (existing.source || "공개 시세") + " · 자동갱신 실패→가격유지";
      note = `자동 갱신 시 KAMIS 오류(${e.message}). 이전 packPrice 유지, 날짜만 ${today}.`;
      mode = "kamis-fallback";
    }
  } else {
    console.log("No KAMIS secrets — rolling date, keeping packPrice");
    source = existing.source?.includes("자동")
      ? existing.source
      : "공개 시세 피드 (자동 날짜 갱신 · 가격은 수동/이전값)";
    note =
      "KAMIS_CERT_KEY / KAMIS_CERT_ID 시크릿이 없어 가격은 유지하고 기준일만 갱신했습니다. " +
      "실시세 자동화를 쓰려면 GitHub repo Secrets에 키를 넣으세요. 키는 앱에 넣지 않습니다.";
    mode = "date-roll";
  }

  // 빠진 사이즈 채우기
  const defaults = {
    so: 5200,
    jung: 5900,
    dae: 6600,
    teuk: 7400,
    wang: 8200,
  };
  for (const id of SIZES) {
    if (!(packPrice[id] > 0)) packPrice[id] = defaults[id];
  }

  const feed = {
    asOf: today,
    source,
    packCount: 30,
    note,
    packPrice,
    updatedAt: new Date().toISOString(),
    autoUpdate: {
      mode,
      timezone: "Asia/Seoul",
      policy: "daily",
    },
  };

  writeFeed(feed);
  console.log("Done", mode, today);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
