<#
.SYNOPSIS
    Queries GitHub releases for rust-lang/rust, mozilla/sccache, and cargo-bins/cargo-binstall
    and updates the plugin's versions.json catalog.
.EXAMPLE
    .\scripts\update-rust-versions.ps1
#>

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$PyScript = Join-Path $ScriptDir "update-rust-versions.py"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host ">>> Updating Rust Version Catalog from GitHub Releases..." -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

$HasPython = $false
try {
    $ver = & python --version 2>$null
    if ($LASTEXITCODE -eq 0) {
        $HasPython = $true
    }
} catch {
    $HasPython = $false
}

if ($HasPython) {
    & python $PyScript
} else {
    Write-Host "Local Python not found. Running updater via Docker python:3.11-slim container..." -ForegroundColor Yellow
    $RootDir = Split-Path -Parent $ScriptDir
    $DockerPath = $RootDir.Replace("\", "/")
    docker run --rm -v "${DockerPath}:/workspace" -w /workspace python:3.11-slim python scripts/update-rust-versions.py
}

if ($LASTEXITCODE -eq 0) {
    Write-Host "`n[SUCCESS] Rust catalog update completed!" -ForegroundColor Green
} else {
    Write-Host "`n[ERROR] Failed to update Rust version catalog." -ForegroundColor Red
}
