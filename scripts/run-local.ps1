param([switch]$SkipDatabase)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
& (Join-Path $PSScriptRoot 'load-env.ps1')
if (-not $SkipDatabase) {
    & docker compose up -d --wait postgres
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
& (Join-Path $projectRoot 'mvnw.cmd') spring-boot:run
exit $LASTEXITCODE
