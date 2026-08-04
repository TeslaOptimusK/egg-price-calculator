/**
 * 공개 시세 피드 자동 갱신
 *
 * env:
 *   KAMIS_CERT_KEY, KAMIS_CERT_ID  — 있으면 KAMIS 조회
 *   KAMIS_RAW_PATH                 — curl 등이 저장한 응답 파일 (선택)
 *
 * 실행: node scripts/update-market-feed.mjs
 */
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import { spawnSync } from "child_process";

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
  { re: /왕\s*란|왕란|2XL|특대/i, id: "wang" },
  { re: /특\s*란|특란|\bXL\b/i, id: "teuk" },
  { re: /대\s*란|대란|\bL\b(?![A-Za-z])/i, id: "dae" },
  { re: /중\s*란|중란|\bM\b(?![A-Za-z])/i, id: "jung" },
  { re: /소\s*란|소란|\bS\b(?![A-Za-z])/i, id: "so" },
  { re: /계란|달걀|난\s/, id: "teuk" }, // 호수 없으면 특란 대용
];

const BROWSER_UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

function todayKst() {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date());
}

function daysAgoKst(n) {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(d);
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
  if (!s || s === "-" || s === "0" || s === "…") return null;
  const n = Number(s);
  return Number.isFinite(n) && n > 0 ? n : null;
}

function unitCount(unit, name) {
  const t = `${unit || ""} ${name || ""}`;
  if (/30\s*개|1\s*판|한\s*판|30구|30\s*입/.test(t)) return 30;
  if (/15\s*개|15구/.test(t)) return 15;
  if (/20\s*개|20구/.test(t)) return 20;
  if (/10\s*개|10구|10\s*입/.test(t)) return 10;
  if (/1\s*개|개당/.test(t)) return 1;
  return 10;
}

function matchSize(name) {
  const n = name || "";
  for (const r of NAME_RULES) {
    if (r.re.test(n)) return r.id;
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
    const keys = Object.keys(node);
    const looksLikeRow =
      keys.some((k) =>
        /item_name|itemName|kind_name|kindName|productName|dpr1|dpr2|price|avg_price|sale_price/i.test(
          k
        )
      ) ||
      (node.item != null && typeof node.item === "object");
    if (looksLikeRow && !Array.isArray(node.item)) out.push(node);
    Object.values(node).forEach((v) => walkItems(v, out));
  }
  return out;
}

function extractPackPrices(data) {
  const items = walkItems(data);
  const buckets = { so: [], jung: [], dae: [], teuk: [], wang: [] };

  for (const row of items) {
    const name = [
      row.item_name,
      row.itemName,
      row.kind_name,
      row.kindName,
      row.productName,
      row.PUM_NM,
      row.kindname,
      row.itemname,
    ]
      .filter(Boolean)
      .join(" ");
    if (!name) continue;
    // 계란 관련 행만
    if (!/계란|달걀|란|난|egg/i.test(name) && !matchSize(name)) continue;

    let sizeId = matchSize(name);
    if (!sizeId && /계란|달걀/.test(name)) sizeId = "teuk";
    if (!sizeId || !buckets[sizeId]) continue;

    const price =
      parsePrice(row.dpr1) ||
      parsePrice(row.dpr2) ||
      parsePrice(row.dpr3) ||
      parsePrice(row.price) ||
      parsePrice(row.avg_price) ||
      parsePrice(row.sale_price) ||
      parsePrice(row.sprice) ||
      parsePrice(row.mid) ||
      parsePrice(row.MAX) ||
      parsePrice(row.max) ||
      parsePrice(row.MIN);

    if (price == null) continue;
    // 비정상적으로 작은/큰 값 스킵 (원/30개 기준 대략 1천~5만)
    if (price < 500 || price > 80000) continue;

    const unit = row.unit || row.unit_name || row.std || row.se || "";
    let pack30 = price;
    // 이미 판 단위로 보이면 그대로, 아니면 환산
    if (!/30\s*개|1\s*판|한\s*판|30구/.test(`${unit} ${name}`)) {
      const cnt = unitCount(unit, name);
      pack30 = (price / cnt) * 30;
    }
    // dpr 이 이미 30개 가격대(5천~1.5만)면 환산 과도 보정
    if (price >= 4000 && price <= 20000 && unitCount(unit, name) === 10) {
      // 10개 단가일 수도, 30개 팩가일 수도 — 이름에 10개 없으면 팩가로 간주
      if (!/10\s*개|10구/.test(`${unit} ${name}`)) pack30 = price;
    }
    buckets[sizeId].push(pack30);
  }

  const packPrice = {};
  for (const id of SIZES) {
    const arr = buckets[id].filter((n) => n > 0).sort((a, b) => a - b);
    if (arr.length) packPrice[id] = Math.round(arr[Math.floor(arr.length / 2)]);
  }
  return {
    packPrice,
    sampleCount: Object.values(buckets).reduce((a, b) => a + b.length, 0),
    rowCount: items.length,
  };
}

function buildUrls(certKey, certId) {
  const key = encodeURIComponent(certKey.trim());
  const id = encodeURIComponent(certId.trim());
  const ymd = todayKst().replace(/-/g, "");
  const start = daysAgoKst(7);
  const end = todayKst();
  const auth = `p_cert_key=${key}&p_cert_id=${id}&p_returntype=json`;

  return [
    // 소매 · 축산물 일별
    `https://www.kamis.or.kr/service/price/xml.do?action=dailyPriceByCategoryList&p_product_cls_code=01&p_item_category_code=500&p_category_code=500&p_regday=${ymd}&${auth}`,
    `https://www.kamis.or.kr/service/price/xml.do?action=dailyPriceByCategoryList&p_product_cls_code=01&p_item_category_code=500&${auth}`,
    // 구 파라미터 이름
    `https://www.kamis.or.kr/service/price/xml.do?action=dailyPriceByCategoryList&p_productclscode=01&p_itemcategorycode=500&${auth}`,
    // 기간 조회 (축산물)
    `https://www.kamis.or.kr/service/price/xml.do?action=periodProductList&p_startday=${start}&p_endday=${end}&p_productclscode=01&p_itemcategorycode=500&p_itemcode=&p_kindcode=&p_productrankcode=&p_convert_kg_yn=N&${auth}`,
    // http 폴백
    `http://www.kamis.or.kr/service/price/xml.do?action=dailyPriceByCategoryList&p_product_cls_code=01&p_item_category_code=500&${auth}`,
  ];
}

function curlGet(url) {
  const r = spawnSync(
    "curl",
    [
      "-sS",
      "-L",
      "--max-time",
      "25",
      "-4", // IPv4 강제 (일부 WAF/406 회피)
      "-A",
      BROWSER_UA,
      "-H",
      "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
      "-H",
      "Accept-Language: ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
      "-H",
      "Connection: close",
      "-w",
      "\n__HTTP_STATUS__:%{http_code}",
      url,
    ],
    { encoding: "utf8", maxBuffer: 10 * 1024 * 1024 }
  );
  if (r.error) throw new Error(`curl failed: ${r.error.message}`);
  const out = r.stdout || "";
  const m = out.match(/\n__HTTP_STATUS__:(\d+)\s*$/);
  const status = m ? Number(m[1]) : 0;
  const body = m ? out.slice(0, m.index) : out;
  return { status, body, stderr: r.stderr || "" };
}

async function nodeGet(url) {
  const res = await fetch(url, {
    headers: {
      "User-Agent": BROWSER_UA,
      Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
      "Accept-Language": "ko-KR,ko;q=0.9,en;q=0.8",
    },
    redirect: "follow",
  });
  const body = await res.text();
  return { status: res.status, body };
}

function parseBody(body) {
  const text = (body || "").trim();
  if (!text) throw new Error("빈 응답");
  if (text.startsWith("<!DOCTYPE") || text.startsWith("<html")) {
    throw new Error("HTML 응답(차단/오류 페이지)");
  }
  // JSON
  try {
    return JSON.parse(text);
  } catch {
    const m = text.match(/\{[\s\S]*\}/);
    if (m) return JSON.parse(m[0]);
  }
  // 간단 XML → 실패 시 에러
  if (text.includes("<document") || text.includes("<?xml")) {
    throw new Error("XML 응답 — JSON 요청 필요");
  }
  throw new Error(`JSON 파싱 실패: ${text.slice(0, 120)}`);
}

async function fetchKamis(certKey, certId) {
  const errors = [];

  // 1) 워크플로가 미리 받아 둔 파일
  const rawPath = process.env.KAMIS_RAW_PATH;
  if (rawPath && fs.existsSync(rawPath)) {
    try {
      const body = fs.readFileSync(rawPath, "utf8");
      const data = parseBody(body);
      const extracted = extractPackPrices(data);
      if (Object.keys(extracted.packPrice).length) {
        console.log("Parsed KAMIS_RAW_PATH", extracted);
        return extracted;
      }
      errors.push(`KAMIS_RAW_PATH: 계란 호수 없음 (rows=${extracted.rowCount})`);
    } catch (e) {
      errors.push(`KAMIS_RAW_PATH: ${e.message}`);
    }
  }

  const urls = buildUrls(certKey, certId);

  for (const url of urls) {
    // curl 우선 (GHA WAF 회피에 유리한 경우 많음)
    try {
      const { status, body } = curlGet(url);
      console.log("curl", status, url.slice(0, 90) + "...");
      if (status === 406) {
        errors.push(`curl 406 ${url.slice(0, 60)}`);
        continue;
      }
      if (status < 200 || status >= 300) {
        errors.push(`curl HTTP ${status}`);
        continue;
      }
      const data = parseBody(body);
      const extracted = extractPackPrices(data);
      if (Object.keys(extracted.packPrice).length) {
        console.log("KAMIS ok via curl", extracted.packPrice, "samples", extracted.sampleCount);
        return extracted;
      }
      // data: ["200"] 형태 인증 오류 코드
      if (Array.isArray(data.data) && data.data[0] && String(data.data[0]).length <= 5) {
        errors.push(`curl API code ${data.data[0]}`);
      } else {
        errors.push(`curl 파싱됨 but no egg sizes (rows=${extracted.rowCount})`);
      }
    } catch (e) {
      errors.push(`curl: ${e.message}`);
    }

    // node fetch 폴백
    try {
      const { status, body } = await nodeGet(url);
      console.log("fetch", status, url.slice(0, 90) + "...");
      if (status === 406) {
        errors.push(`fetch 406`);
        continue;
      }
      if (status < 200 || status >= 300) {
        errors.push(`fetch HTTP ${status}`);
        continue;
      }
      const data = parseBody(body);
      const extracted = extractPackPrices(data);
      if (Object.keys(extracted.packPrice).length) {
        console.log("KAMIS ok via fetch", extracted.packPrice);
        return extracted;
      }
      if (Array.isArray(data.data) && data.data[0]) {
        errors.push(`fetch API code ${data.data[0]}`);
      } else {
        errors.push(`fetch no egg sizes`);
      }
    } catch (e) {
      errors.push(`fetch: ${e.message}`);
    }
  }

  throw new Error(errors.slice(0, 8).join(" | "));
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
  const key = (process.env.KAMIS_CERT_KEY || process.env.KAMIS_KEY || "").trim();
  const id = (process.env.KAMIS_CERT_ID || process.env.KAMIS_ID || "").trim();

  let packPrice = { ...(existing.packPrice || {}) };
  let source = existing.source || "앱 공개 시세 피드";
  let note = existing.note || "";
  let mode = "date-roll";

  if (key && id) {
    try {
      const r = await fetchKamis(key, id);
      packPrice = { ...packPrice, ...r.packPrice };
      source = `KAMIS 소매 자동갱신 (${today})`;
      note = `GitHub Actions가 하루 1회 KAMIS를 조회해 갱신했습니다. 샘플 ${r.sampleCount}건. 공식 공시·지역·단위와 다를 수 있습니다.`;
      mode = "kamis";
      console.log("KAMIS ok", packPrice);
    } catch (e) {
      console.warn("KAMIS failed, keep previous prices:", e.message);
      source = `${existing.source || "공개 시세"} · 자동갱신 실패→가격유지`;
      note = `자동 갱신 시 KAMIS 오류(${e.message}). 이전 packPrice 유지, 날짜만 ${today}.`;
      mode = "kamis-fallback";
    }
  } else {
    console.log("No KAMIS secrets — rolling date, keeping packPrice");
    source = "공개 시세 피드 (자동 날짜 갱신 · 가격은 수동/이전값)";
    note =
      "KAMIS_CERT_KEY / KAMIS_CERT_ID 시크릿이 없어 가격은 유지하고 기준일만 갱신했습니다.";
    mode = "date-roll";
  }

  const defaults = {
    so: 5200,
    jung: 5900,
    dae: 6600,
    teuk: 7400,
    wang: 8200,
  };
  for (const sid of SIZES) {
    if (!(packPrice[sid] > 0)) packPrice[sid] = defaults[sid];
  }

  writeFeed({
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
  });
  console.log("Done", mode, today);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
