# 계란 단가 계산기 — 진행 현황 (이어하기용)

**마지막 업데이트:** 2026-08-03 (세션 종료 시점)  
**목적:** 내일 Grok/본인이 이 파일만 보고 이어서 작업

---

## 한 줄 요약

앱·웹·GitHub 자동화 골격은 완료.  
**Play Console 신원 확인** · **KAMIS OpenAPI 승인** 대기 중.  
승인 나면 Secrets 등록 + Actions 확인 + (선택) Play 앱 등록으로 이어가면 됨.

---

## 완료된 것

### 앱 기능
- [x] 계란 호수·가격·개수 → 10g당 단가 (껍질 포함/제외)
- [x] 두 상품 비교 · **같은 사이즈만** 옵션
- [x] 가격 저장 (시간·위치) · 이력 · Leaflet 지도
- [x] 시세 대비 저렴/비쌈 의견 (동일 호수만)
- [x] **하루 1회** 시세 갱신 정책 (버튼 연타 방지)
- [x] 사용자 **API 키 불필요** (공개 피드 + 내장 JSON)
- [x] UI iOS 스타일 리디자인 (웹 + 안드 톤 맞춤)

### GitHub (2단계 자동화)
- [x] 저장소: https://github.com/TeslaOptimusK/egg-price-calculator
- [x] `main` 푸시 완료 (초기 커밋)
- [x] Actions 워크플로: `.github/workflows/daily-market-feed.yml`
- [x] 스크립트: `scripts/update-market-feed.mjs`
- [x] raw URL 앱 연결:
  - 웹: `web/feed-config.json`
  - 안드: `strings.xml` → `market_feed_remote_url`
  - URL: `https://raw.githubusercontent.com/TeslaOptimusK/egg-price-calculator/main/web/market-live.json`
- [x] 문서: `docs/AUTO-MARKET-FEED.md`, `README.md`, `SPEC.md`

### Google Play
- [x] 개발자 계정 등록 진행 (개인, 표시명 예: Tesla_Optimus)
- [x] 본인 확인·주소 증빙 등 제출 → **신원 확인 중**이었음
- [ ] 신원 승인 완료 여부 → **내일 콘솔에서 확인**
- [ ] 첫 앱 만들기 / AAB 업로드 → 승인 후

### KAMIS
- [x] OpenAPI 이용신청 진행 (업체구분: **일반**)
- [ ] 승인 · 키 발급 대기
- [ ] GitHub Secrets 등록:
  - `KAMIS_CERT_KEY`
  - `KAMIS_CERT_ID`
- [ ] Actions **Daily market feed** 수동 1회 성공 확인

---

## 로컬 경로

```
D:\Grok\egg-price-calculator\
  web\          ← 브라우저로 index.html
  android\      ← Android Studio
  scripts\      ← 시세 피드 스크립트
  .github\workflows\daily-market-feed.yml
  PROGRESS.md   ← 이 파일
```

Git: `D:\Grok\egg-price-calculator` 가 저장소 루트  
remote: `origin` → `https://github.com/TeslaOptimusK/egg-price-calculator.git`

---

## 내일 이어할 순서 (체크리스트)

### A. 승인 상태 확인
1. [ ] [Play Console](https://play.google.com/console) — 신원 확인 완료 여부
2. [ ] KAMIS 메일/사이트 — OpenAPI 승인·키 여부

### B. KAMIS 승인됐으면
1. [ ] GitHub → Settings → Secrets → Actions  
       `KAMIS_CERT_KEY`, `KAMIS_CERT_ID` 추가
2. [ ] Actions → Daily market feed → Run workflow
3. [ ] `web/market-live.json` 커밋·내용(asOf, packPrice) 확인
4. [ ] raw URL 브라우저에서 JSON 열리는지 확인

### C. Play 신원 승인됐으면
1. [ ] 첫 앱 만들기 (applicationId 확정: 현재 `com.eggprice.calc` — 출시 전 변경 검토)
2. [ ] release keystore + AAB
3. [ ] 스토어 등록정보 · 개인정보 처리방침 · 데이터 안전성
4. [ ] 내부 테스트 → 프로덕션

### D. 키/승인 아직이면
- 앱 개발·UI·웹 테스트는 계속 가능 (참고 시세로 비교)
- 서버 구축 불필요

---

## Grok에게 내일 첫 메시지로 복붙 추천

```
egg-price-calculator 이어서 진행.
PROGRESS.md 읽고 현재 상태 확인한 뒤,
Play Console / KAMIS 승인 여부에 맞춰 다음 단계 진행해 줘.
저장소: TeslaOptimusK/egg-price-calculator
경로: D:\Grok\egg-price-calculator
```

승인 결과(승인/대기/거절 사유)를 같이 적어 주면 더 빠름.

---

## 참고 링크

| 항목 | URL |
|------|-----|
| GitHub repo | https://github.com/TeslaOptimusK/egg-price-calculator |
| 시세 raw JSON | https://raw.githubusercontent.com/TeslaOptimusK/egg-price-calculator/main/web/market-live.json |
| Play Console | https://play.google.com/console |
| KAMIS OpenAPI | https://www.kamis.or.kr/customer/reference/openapi_list.do |
| 자동화 가이드 | `docs/AUTO-MARKET-FEED.md` |

---

## 설계 결정 메모 (바꾸지 말 것 · 합의됨)

- 사용자에게 KAMIS 키 요구하지 않음
- 시세는 **하루 1회** (KST 기준)
- 시세 비교는 **같은 사이즈(호수)만**
- 2단계 = GitHub Actions + raw JSON (서버 VPS 불필요)
- 3단계 Workers는 트래픽·즉시성 필요할 때만
