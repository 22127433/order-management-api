$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    throw 'Copy .env.example to .env and set POSTGRES_PASSWORD first.'
}

# Parse values as data; never evaluate .env contents as PowerShell code.
foreach ($line in Get-Content -LiteralPath $envFile -Encoding UTF8) {
    $trimmed = $line.Trim()
    if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) { continue }
    if ($trimmed -notmatch '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
        throw 'Invalid .env line. Expected KEY=value.'
    }
    $key = $Matches[1]
    $value = $Matches[2].Trim()
    if ($value.Length -ge 2 -and
        (($value.StartsWith("'") -and $value.EndsWith("'")) -or
         ($value.StartsWith('"') -and $value.EndsWith('"')))) {
        $value = $value.Substring(1, $value.Length - 2)
    }
    [Environment]::SetEnvironmentVariable($key, $value, 'Process')
}

if ([string]::IsNullOrWhiteSpace($env:POSTGRES_PASSWORD)) {
    throw 'POSTGRES_PASSWORD must not be empty.'
}
