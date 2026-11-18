param([string]$EnvFile = (Join-Path (Split-Path -Parent $PSScriptRoot) '.env'))

$ErrorActionPreference = 'Stop'
$template = Join-Path (Split-Path -Parent $PSScriptRoot) '.env.example'
if (-not (Test-Path -LiteralPath $EnvFile)) { Copy-Item -LiteralPath $template -Destination $EnvFile }
$configuration = [System.IO.File]::ReadAllText($EnvFile)
$match = [regex]::Match($configuration, '(?m)^JWT_SECRET_BASE64=([^\r\n]*)')
$existing = if ($match.Success) { $match.Groups[1].Value.Trim().Trim("'", '"') } else { '' }
if ($existing) {
    Write-Host 'JWT secret already configured; kept unchanged.'
    return
}
$bytes = New-Object byte[] 32
$random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $random.GetBytes($bytes) } finally { $random.Dispose() }
$newLine = 'JWT_SECRET_BASE64=' + [Convert]::ToBase64String($bytes)
if ($match.Success) {
    $configuration = [regex]::Replace($configuration, '(?m)^JWT_SECRET_BASE64=[^\r\n]*', $newLine)
} else {
    $configuration = $configuration.TrimEnd() + [Environment]::NewLine + $newLine + [Environment]::NewLine
}
[System.IO.File]::WriteAllText($EnvFile, $configuration, (New-Object System.Text.UTF8Encoding($false)))
Write-Host 'Generated a 32-byte JWT secret in the local environment file; its value is not printed.'
Write-Host 'Set POSTGRES_PASSWORD and, if needed, one-time BOOTSTRAP_ADMIN credentials before startup.'
