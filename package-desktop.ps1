# Build a self-contained Windows Setup EXE. Never reads private deployment profiles.
param(
    [string]$Version = ("local-" + (Get-Date -Format "yyyyMMdd-HHmmss")),
    [switch]$PortableZip
)
$ErrorActionPreference = "Stop"
Set-Location -LiteralPath $PSScriptRoot
if ($Version -notmatch '^[A-Za-z0-9][A-Za-z0-9_.-]{0,80}$') { throw "Invalid version" }
$venv = Join-Path $PSScriptRoot "release\desktop-build-env"
if (-not (Test-Path -LiteralPath (Join-Path $venv "Scripts\python.exe"))) {
    python -m venv $venv
    if ($LASTEXITCODE -ne 0) { throw "Creating packaging environment failed" }
}
$python = Join-Path $venv "Scripts\python.exe"
& $python -m pip install -r distribution/requirements-build.txt
if ($LASTEXITCODE -ne 0) { throw "Installing build dependencies failed" }
$payload = Join-Path $PSScriptRoot "release\payload-$Version-amd64"
& $python distribution/build_images.py --arch amd64 --version $Version --output $payload --prefer-local-databases
if ($LASTEXITCODE -ne 0) { throw "Building application images failed" }
$packageArgs = @('distribution/package.py', '--payload', $payload)
if ($PortableZip) { $packageArgs += '--windows-portable' }
& $python @packageArgs
if ($LASTEXITCODE -ne 0) { throw "Packaging release failed" }
