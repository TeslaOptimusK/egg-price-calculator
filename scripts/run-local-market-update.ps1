# KAMIS는 GitHub Actions(클라우드 IP)를 웹방화벽으로 차단(HTTP 406)합니다.
# 이 스크립트는 집/회사 PC(일반 IP)에서 하루 1회 실행 → JSON 갱신 → git push
#
# 사용 전 (한 번):
#   1) 아래 환경변수 설정 또는 .env.local 작성 (gitignore 됨)
#   2) gh auth login 완료
#
# 실행:
#   cd D:\Grok\egg-price-calculator
#   powershell -ExecutionPolicy Bypass -File scripts\run-local-market-update.ps1

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $Root

# .env.local 로드 (KEY=VALUE 형식, 주석 # 허용)
$envFile = Join-Path $Root ".env.local"
if (Test-Path $envFile) {
  Get-Content $envFile | ForEach-Object {
    $line = $_.Trim()
    if (-not $line -or $line.StartsWith("#")) { return }
    $i = $line.IndexOf("=")
    if ($i -lt 1) { return }
    $k = $line.Substring(0, $i).Trim()
    $v = $line.Substring($i + 1).Trim().Trim('"').Trim("'")
    [Environment]::SetEnvironmentVariable($k, $v, "Process")
  }
  Write-Host "Loaded .env.local"
}

if (-not $env:KAMIS_CERT_KEY -or -not $env:KAMIS_CERT_ID) {
  Write-Error "KAMIS_CERT_KEY / KAMIS_CERT_ID 가 없습니다. .env.local 또는 환경변수를 설정하세요."
}

Write-Host "Updating market feed via KAMIS (local IP)..."
node scripts\update-market-feed.mjs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

git add web/market-live.json android/app/src/main/assets/market-live.json
git diff --staged --quiet
if ($LASTEXITCODE -eq 0) {
  Write-Host "No changes to commit"
  exit 0
}

$day = Get-Date -Format "yyyy-MM-dd"
git -c user.email="TeslaOptimusK@users.noreply.github.com" -c user.name="TeslaOptimusK" `
  commit -m "chore(market): local KAMIS feed update $day"
git push origin main
Write-Host "Pushed market-live.json to GitHub. Apps will pick it up on next daily refresh."
