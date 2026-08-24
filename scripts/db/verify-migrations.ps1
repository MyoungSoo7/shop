# 전 서비스 Flyway 마이그레이션 실적용 검증 (PostgreSQL 17 + Flyway CLI, 도커)
# 사용: powershell -File scripts/db/verify-migrations.ps1
$ErrorActionPreference = "Continue"
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$pgName = "dbcheck-pg"
$net = "dbcheck"
$pw = "dbcheck"

# 서비스 → (DB명, 스키마) 매핑 (application.yml flyway 설정과 동일)
$services = @(
    @{ name = "order-service";                db = "opslab";             schema = "opslab" },
    @{ name = "settlement-service";           db = "settlement_db";      schema = "public" },
    @{ name = "loan-service";                 db = "lemuel_loan";        schema = "opslab" },
    @{ name = "financial-statements-service"; db = "lemuel_financial";   schema = "public" },
    @{ name = "economics-service";            db = "lemuel_economics";   schema = "public" },
    @{ name = "company-service";              db = "lemuel_company";     schema = "public" },
    @{ name = "operation-service";            db = "lemuel_operation";   schema = "opslab" },
    @{ name = "market-service";               db = "lemuel_market";      schema = "public" },
    @{ name = "common-data-service";          db = "lemuel_commondata";  schema = "public" },
    @{ name = "investment-service";           db = "lemuel_investment";  schema = "opslab" },
    @{ name = "account-service";              db = "lemuel_account";     schema = "opslab" },
    @{ name = "organization-service";         db = "lemuel_org";         schema = "opslab" }
)

docker rm -f $pgName 2>$null | Out-Null
docker network rm $net 2>$null | Out-Null
docker network create $net | Out-Null
docker run -d --name $pgName --network $net -e POSTGRES_PASSWORD=$pw postgres:17-alpine | Out-Null

$ready = $false
for ($i = 0; $i -lt 30; $i++) {
    docker exec $pgName pg_isready -U postgres 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    Start-Sleep -Seconds 1
}
if (-not $ready) { Write-Output "FATAL: postgres not ready"; exit 1 }

$fail = 0
foreach ($svc in $services) {
    $dir = Join-Path $root "$($svc.name)\src\main\resources\db\migration"
    if (-not (Test-Path $dir)) { Write-Output "SKIP  $($svc.name) (no migrations)"; continue }
    docker exec $pgName createdb -U postgres $svc.db 2>$null | Out-Null
    # 주의: PS5.1 은 대시로 시작하는 bare 토큰 안의 $변수를 치환하지 않는다 — 토큰 전체를 따옴표로 감쌀 것
    $out = docker run --rm --network $net -v "${dir}:/flyway/sql" flyway/flyway:11-alpine `
        "-url=jdbc:postgresql://${pgName}:5432/$($svc.db)" "-user=postgres" "-password=$pw" `
        "-schemas=$($svc.schema)" "-defaultSchema=$($svc.schema)" "-createSchemas=true" `
        "-validateMigrationNaming=true" migrate 2>&1
    if ($LASTEXITCODE -eq 0) {
        $applied = ($out | Select-String "Successfully applied").Line
        Write-Output "PASS  $($svc.name)  $applied"
    } else {
        $fail++
        Write-Output "FAIL  $($svc.name)"
        Write-Output ($out | Select-Object -Last 25)
    }
}

docker rm -f $pgName 2>$null | Out-Null
docker network rm $net 2>$null | Out-Null

Write-Output "----"
if ($fail -eq 0) { Write-Output "ALL GREEN" } else { Write-Output "$fail service(s) FAILED"; exit 1 }
