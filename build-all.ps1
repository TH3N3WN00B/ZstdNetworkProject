# ZstdNetworkProject - build-all.ps1
# Windows twin of build-all.sh: builds every Minecraft version group
# (gradle-mc<version>.properties) plus the version-independent Velocity plugin,
# and collects all jars into dist/.
#
# For each group it builds neoforge and fabric; paper is only built when the
# group defines a real paper_version (not NONE). A failing group does not abort
# the remaining groups nor the Velocity build: it is recorded and the script
# exits non-zero at the very end.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File build-all.ps1            # every group
#   powershell -ExecutionPolicy Bypass -File build-all.ps1 1.21.4 26.2

param(
    [string[]] $Versions = @()
)

$ErrorActionPreference = "Stop"
$PROJECT_ROOT = if ($PSScriptRoot) { $PSScriptRoot } else { "A:/IdeaProjects/ZstdNetworkProject" }
$GRADLEW = Join-Path $PROJECT_ROOT "gradlew.bat"
$dist = Join-Path $PROJECT_ROOT "dist"

$modules = @('neoforge', 'fabric', 'paper')
$versionKey = @{
    'neoforge' = 'neoforge_version'
    'fabric'   = 'fabric_api_version'
    'paper'    = 'paper_version'
}

function Get-GroupFiles {
    param([string[]] $Wanted)
    $files = Get-ChildItem -Path $PROJECT_ROOT -Filter 'gradle-mc*.properties' |
        Sort-Object Name
    if ($Wanted.Count -gt 0) {
        $files = $files | Where-Object {
            $v = $_.Name.Substring('gradle-mc'.Length).TrimEnd('.properties')
            $Wanted -contains $v
        }
    }
    foreach ($f in $files) {
        [PSCustomObject]@{
            Version = $f.Name.Substring('gradle-mc'.Length).TrimEnd('.properties')
            Path    = $f.FullName
        }
    }
}

function Parse-Props {
    param([string] $Path)
    $props = @{}
    foreach ($line in Get-Content -Path $Path) {
        $line = $line.Trim()
        if ($line -eq '' -or $line.StartsWith('#')) { continue }
        $eq = $line.IndexOf('=')
        if ($eq -le 0) { continue }
        $props[$line.Substring(0, $eq).Trim()] = $line.Substring($eq + 1).Trim()
    }
    return $props
}

function Test-ModuleVersion {
    param([string] $Value)
    return -not [string]::IsNullOrWhiteSpace($Value) -and $Value -ne 'NONE'
}

# mod_version and velocity_version (gradle.properties) drive the jar names and manifests.
$modVersion = ''
$velocityVersion = ''
foreach ($line in Get-Content (Join-Path $PROJECT_ROOT 'gradle.properties')) {
    if ($line.Trim().StartsWith('mod_version=')) {
        $modVersion = $line.Trim().Substring('mod_version='.Length).Trim("`r", "`n")
    } elseif ($line.Trim().StartsWith('velocity_version=')) {
        $velocityVersion = $line.Trim().Substring('velocity_version='.Length).Trim("`r", "`n")
    }
}
if ([string]::IsNullOrWhiteSpace($modVersion)) {
    Write-Host "ERROR: mod_version not found in gradle.properties" -ForegroundColor Red
    exit 1
}
Write-Host "mod_version=$modVersion"
Write-Host "velocity_version=$velocityVersion"

New-Item -ItemType Directory -Force -Path $dist | Out-Null
Get-ChildItem -Path $dist -Filter '*.jar' -ErrorAction SilentlyContinue | Remove-Item -Force

$failed = New-Object 'System.Collections.Generic.List[string]'

foreach ($group in Get-GroupFiles $Versions) {
    Write-Host "=== Building Minecraft $($group.Version) ==="
    $props = Parse-Props $group.Path
    $gradleArgs = @()
    foreach ($kv in $props.GetEnumerator()) {
        $gradleArgs += "-P$($kv.Key)=$($kv.Value)"
    }
    $tasks = @()
    foreach ($module in $modules) {
        $key = $versionKey[$module]
        if ($props.ContainsKey($key) -and (Test-ModuleVersion $props[$key])) {
            $tasks += ":$($module):build"
        }
    }
    if ($tasks.Count -eq 0) {
        Write-Host "  (no modules to build for this group)" -ForegroundColor Yellow
        continue
    }
    Push-Location $PROJECT_ROOT
    try {
        & $GRADLEW @tasks @gradleArgs --console=plain
        if ($LASTEXITCODE -ne 0) {
            Write-Host "BUILD FAILED for $($group.Version)" -ForegroundColor Red
            $failed.Add($group.Version)
        } else {
            foreach ($module in $modules) {
                $key = $versionKey[$module]
                if (-not $props.ContainsKey($key) -or -not (Test-ModuleVersion $props[$key])) {
                    continue
                }
                $jars = Get-ChildItem -Path (Join-Path $PROJECT_ROOT "$module\build\libs") `
                    -Filter "*-$modVersion-mc$($group.Version).jar" -ErrorAction SilentlyContinue |
                    Where-Object { $_.Name -notmatch 'sources|javadoc|dev' }
                foreach ($jar in $jars) {
                    Copy-Item -Path $jar.FullName -Destination $dist
                }
            }
        }
    } finally {
        Pop-Location
    }
}

Write-Host '=== Building Velocity ==='
Push-Location $PROJECT_ROOT
try {
    & $GRADLEW ':velocity:build' --console=plain
    if ($LASTEXITCODE -ne 0) {
        Write-Host "BUILD FAILED for velocity" -ForegroundColor Red
        $failed.Add('velocity')
    } else {
        $jars = Get-ChildItem -Path (Join-Path $PROJECT_ROOT 'velocity\build\libs') `
            -Filter "*-$modVersion-$velocityVersion.jar" -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch 'sources|javadoc|dev' }
        foreach ($jar in $jars) {
            Copy-Item -Path $jar.FullName -Destination $dist
        }
    }
} finally {
    Pop-Location
}

Write-Host ""
Write-Host "Artifacts in: $dist"
$copied = Get-ChildItem -Path $dist -Filter '*.jar' -ErrorAction SilentlyContinue |
    Sort-Object Name
foreach ($jar in $copied) {
    Write-Host ("  {0} ({1} MB)" -f $jar.Name, [math]::Round($jar.Length / 1MB, 2))
}

if ($failed.Count -gt 0) {
    Write-Host "FAILED targets: $($failed -join ', ')" -ForegroundColor Red
    exit 1
}
if ($copied.Count -eq 0) {
    Write-Host "No jars were produced - check the build output above." -ForegroundColor Yellow
    exit 1
}
Write-Host "All groups built successfully." -ForegroundColor Green