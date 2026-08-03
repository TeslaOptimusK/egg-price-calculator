# 시세 피드 자동화 (2단계 · 서버 없이)

앱 사용자는 **키·서버 불필요**.  
운영자만 GitHub에 올리면 **하루 1회 JSON이 자동 갱신**됩니다.

## 구조

```
GitHub Actions (매일)
    ↓  (선택: Secrets의 KAMIS 키)
  scripts/update-market-feed.mjs
    ↓
  web/market-live.json
  android/.../assets/market-live.json
    ↓  commit & push
  raw.githubusercontent.com/.../market-live.json
    ↓  앱이 하루 1회 fetch
  사용자 시세 비교
```

서버(VPS/Workers)를 안 띄워도 됩니다. **CI가 피드를 갱신**합니다.

## 설정 순서

### 1. GitHub에 저장소 푸시

`egg-price-calculator` 폴더를 저장소로 올립니다.  
워크플로는 `.github/workflows/daily-market-feed.yml` 에 있습니다.

### 2. (권장) KAMIS 키를 Secrets에만 저장

Repo → **Settings → Secrets and variables → Actions**

| Name | 값 |
|------|-----|
| `KAMIS_CERT_KEY` | KAMIS OpenAPI 키 |
| `KAMIS_CERT_ID` | 신청 아이디 |

- 키는 **앱/APK에 넣지 마세요.**
- Secrets 없으면: **가격 유지 + 날짜만 오늘로** 갱신 (파이프라인은 동작).

### 3. 앱에 raw URL 연결 (한 번만)

1. 브라우저에서 확인:
   `https://raw.githubusercontent.com/<USER>/<REPO>/main/web/market-live.json`
2. `web/feed-config.json` 수정:

```json
{
  "remoteFeeds": [
    "https://raw.githubusercontent.com/<USER>/<REPO>/main/web/market-live.json"
  ]
}
```

3. 안드로이드: `res/values/strings.xml` 의 `market_feed_remote_url` 에 같은 URL.

### 4. 수동 테스트

- GitHub → Actions → **Daily market feed** → **Run workflow**
- 또는 로컬:

```bash
cd egg-price-calculator
# 선택: export KAMIS_CERT_KEY=... KAMIS_CERT_ID=...
node scripts/update-market-feed.mjs
```

## 비용

| 항목 | 비용 |
|------|------|
| GitHub Actions (공개 저장소·소량) | 보통 **무료** |
| KAMIS 키 | 발급 자체 **무료**(한도 있음) |
| raw.githubusercontent.com | **무료** |
| 사용자 | **0원 · 키 없음** |

## 한계

- Actions 지연·실패 시 이전 JSON 유지
- KAMIS 응답 구조 변경 시 스크립트 수정 필요
- raw URL은 캐시될 수 있음 → 앱은 하루 1회 + `fetchDay` 정책

## 3단계(Workers)와 차이

| | 2단계 자동화 (지금) | 3단계 Workers |
|--|---------------------|---------------|
| 인프라 | GitHub만 | 워커 URL |
| 갱신 | 하루 1회 커밋 | 요청 시 캐시 조회 |
| 적합성 | 개인·소규모 출시 | 트래픽·즉시성 더 필요할 때 |
