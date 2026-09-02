# Builds every Minecraft version group (gradle-mc<version>.properties) plus the
# version-independent Velocity plugin, and collects all jars into dist/.
#
# For each version group it builds neoforge and fabric; the paper module is only
# built when the group defines a real paper_version (not NONE).
#
# Usage (PowerShell):
#   .\build-all.ps1               # build all version groups
#   .\build-all.ps1 1.21.4 26.2   # build specific versions
param(
    [string[]]$Versions = @()
)

$root = $PSScriptRoot
$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null

# Clean up obsolete jars and old build logs
Remove-Item (Join-Path $dist '*.jar') -Force -ErrorAction SilentlyContinue
Get-ChildItem -Path $root -Filter 'build-*.log' -File | Remove-Item -Force -ErrorAction SilentlyContinue

if ($Versions.Count -eq 0) {
    $Versions = Get-ChildItem -Path $root -Filter 'gradle-mc*.properties' -File |
        ForEach-Object { $_.BaseName -replace '^gradle-mc', '' } | Sort-Object
}

$currentModVersion = (Get-Content (Join-Path $root 'gradle.properties') |
    Where-Object { $_ -match '^\s*mod_version\s*=\s*(.+?)\s*$' } |
    ForEach-Object { $matches[1].Trim() })

foreach ($version in $Versions) {
    $props = Join-Path $root "gradle-mc$version.properties"
    if (-not (Test-Path $props)) {
        Write-Host "SKIP: no $props" -ForegroundColor Yellow
        continue
    }

    $propMap = @{}
    Get-Content $props | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z0-9_.-]+)\s*=\s*(.+?)\s*$') {
            $propMap[$matches[1]] = $matches[2]
        }
    }

    $gradleArgs = @('--console=plain')
    foreach ($key in $propMap.Keys) {
        $gradleArgs += "-P$key=$($propMap[$key])"
    }

    $tasks = @(':neoforge:build', ':fabric:build')
    if ($propMap.ContainsKey('paper_version') -and $propMap['paper_version'] -ne 'NONE') {
        $tasks += ':paper:build'
    }
    $gradleArgs = $tasks + $gradleArgs

    Write-Host "=== Building Minecraft $version ===" -ForegroundColor Cyan
    Push-Location $root
    & .\gradlew.bat @gradleArgs
    $code = $LASTEXITCODE
    Pop-Location

    if ($code -ne 0) {
        Write-Host "BUILD FAILED for $version" -ForegroundColor Red
        continue
    }

    foreach ($module in @('neoforge', 'fabric', 'paper')) {
        # Skip paper when the group file has no paper_version at all, not only when it
        # says NONE: otherwise stale paper jars from an earlier group get copied to dist.
        if ($module -eq 'paper' -and
            (-not $propMap.ContainsKey('paper_version') -or $propMap['paper_version'] -eq 'NONE')) {
            continue
        }
        $libs = Join-Path $root "$module\build\libs"
        if (Test-Path $libs) {
            Get-ChildItem $libs -Filter '*.jar' -File |
                Where-Object { $_.Name -notmatch 'sources|javadoc|dev' -and $_.Name -like "*-$currentModVersion-mc$version.jar" } |
                Copy-Item -Destination $dist -Force
        }
    }
}

# Version-independent plugin: build once against the default (Java 25 era).
Write-Host '=== Building Velocity ===' -ForegroundColor Cyan
Push-Location $root
& .\gradlew.bat ':velocity:build' '--console=plain'
$code = $LASTEXITCODE
Pop-Location
if ($code -eq 0) {
    Get-ChildItem (Join-Path $root 'velocity\build\libs') -Filter '*.jar' -File |
        Where-Object { $_.Name -notmatch 'sources|javadoc|dev' -and $_.Name -like "*-$currentModVersion.jar" } |
        Copy-Item -Destination $dist -Force
}

Write-Host "Artifacts in: $dist" -ForegroundColor Green
Get-ChildItem $dist -Filter '*.jar' -File | Select-Object -ExpandProperty Name
