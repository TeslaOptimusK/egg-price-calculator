# 계란 단가 계산기

대란·특란 등 **호수**와 **가격·개수**를 넣으면, 껍질을 뺀 **알맹이 10g당 단가**를 계산·비교하는 앱입니다.

| 경로 | 설명 |
|------|------|
| `SPEC.md` | 계산 규격 (호수 중량·알맹이 비율) |
| `web/` | **지금 바로** 브라우저에서 사용 |
| `android/` | Play 스토어용 Kotlin 앱 소스 |
| `tests/test_calc.py` | 계산 검증 스크립트 |

---

## 1. 지금 바로 쓰기 (웹)

PowerShell:

```powershell
cd D:\Grok\egg-price-calculator\web
start index.html
```

또는 폴더에서 `index.html` 더블클릭.

- 한 상품 계산 / 두 상품 비교
- 개수 프리셋 10·15·20·**30(한판)**
- 알맹이 비율 슬라이더 (기본 89%)
- **가격 저장** (시간·위치) · **이력·지도** (Leaflet)
- **KAMIS 실시간 시세** (키 필요) · **같은 사이즈만** 비교
- 시세 대비 저렴/비쌈 의견

### 시세 비교 — 사용자 키 불필요 · 하루 1회

- 기본: 앱 안 **공개 시세 피드** (`market-live.json`) + 기본 참고가  
- **하루 1회**만 갱신 (버튼 연타·서버 부하 방지)  
- Play 사용자에게 API 키를 요구하지 않습니다.

### 2단계 자동화 (서버 없이 · 권장)

GitHub Actions가 매일 `market-live.json`을 갱신합니다.

1. 이 폴더를 GitHub에 푸시  
2. (선택) repo **Secrets**: `KAMIS_CERT_KEY`, `KAMIS_CERT_ID` → 실시세  
3. Secrets 없으면 날짜만 갱신·가격 유지  
4. raw URL을 `web/feed-config.json` · 안드 `market_feed_remote_url` 에 한 번 설정  

상세: [`docs/AUTO-MARKET-FEED.md`](docs/AUTO-MARKET-FEED.md)

```bash
node scripts/update-market-feed.mjs
```

---

## 2. Android 앱 빌드 (Play 출시)

### 준비

1. [Android Studio](https://developer.android.com/studio) 설치  
2. SDK 34, JDK 17  
3. 이 폴더 열기: `egg-price-calculator/android`

### 실행

1. Android Studio → **Open** → `android` 폴더  
2. Gradle Sync  
3. Run (에뮬레이터 또는 실기기)  
4. 릴리스: **Build → Generate Signed Bundle / APK** → Play Console 업로드  

`applicationId`: `com.eggprice.calc`  
(출시 전 본인 도메인으로 변경 권장)

### 스토어 문구 초안

**앱 이름:** 계란 단가 계산기  

**간단한 설명:**  
대란·특란·한판 가격을 알맹이(껍질 제외) 10g당 단가로 바꿔 비교합니다.

**자세한 설명:**  
마트에서 특란 30구와 대란 30구 중 뭐가 이득인지 헷갈릴 때 쓰세요.  
사이즈(왕란~소란), 개수, 가격만 입력하면 먹을 수 있는 알맹이 기준 g당·10g당 단가를 바로 보여 줍니다. 두 상품 비교 모드와 1개 실측 중량 입력도 지원합니다.  
오프라인 계산, 개인정보 수집 없음.

**카테고리:** 쇼핑 또는 생산성  
**콘텐츠 등급:** 전체  

---

## 3. 계산 요약

```
총 알맹이 g = (1개 대표 g) × 0.89 × 개수
10g당 원    = 가격 ÷ 총 알맹이 g × 10
```

| 호수 | 대표 g |
|------|--------|
| 왕란 | 70 |
| 특란 | 64 |
| 대란 | 56 |
| 중란 | 48 |
| 소란 | 40 |

상세: `SPEC.md`

---

## 4. 테스트

```powershell
python D:\Grok\egg-price-calculator\tests\test_calc.py
```

예시 출력: 특란 30구 7800원 → 알맹이 10g당 약 **46원**.

---

## 5. 다음 단계 (선택)

- [ ] Play Console 앱 등록 · 개인정보 처리방침 페이지  
- [ ] `applicationId` / 서명 키 설정  
- [ ] 스크린샷 2~4장 (웹 화면 캡처로 임시 가능)  
- [ ] AdMob (선택)  
- [ ] 가격 이력 저장 (SharedPreferences)  

문의·기능 추가는 이 폴더 기준으로 이어서 작업하면 됩니다.
