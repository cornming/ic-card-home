<#
  Build the signed release APK and publish it as a GitHub Release.
  The installed app checks the latest release and offers it as an update.

  Usage (from the repo root):
    .\scripts\release.ps1 -Notes "What changed"
    .\scripts\release.ps1 -Notes "..." -SkipBuild     # reuse the APK that is already built

  Needs: the release key in ~/.gradle/gradle.properties (ICCARD_KEYSTORE_*), and `gh auth login`.
  The version comes from versionName/versionCode in app/build.gradle.kts: bump both before releasing.
#>
param(
    [Parameter(Mandatory = $true)][string]$Notes,
    [switch]$SkipBuild
)
$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)

$gradle = Get-Content "app\build.gradle.kts" -Raw
$version = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
if (-not $version) { throw "versionName not found in app/build.gradle.kts" }
$tag = "v$version"

if (gh release view $tag 2>$null) { throw "Release $tag already exists. Bump versionCode and versionName first." }

if (-not $SkipBuild) {
    if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }
    .\gradlew.bat --console=plain :app:assembleRelease
    if ($LASTEXITCODE -ne 0) { throw "Build failed" }
}

$built = "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $built)) { throw "$built not found" }
New-Item -ItemType Directory -Force -Path dist | Out-Null
$apk = "dist\ic-card-home-$tag.apk"
Copy-Item $built $apk -Force
$sha = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()

$body = "$Notes`n`nSHA-256: ``$sha```n"
gh release create $tag $apk --title $tag --notes $body
Write-Host "Published $tag ($apk, sha256 $sha)"
