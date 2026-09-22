# ZstdNetworkProject - Beta 0.3.1 Build Script
# Compiles and tests the project locally with a local JDK + the Gradle wrapper.

param(
    [switch] $Clean,
    [switch] $Test,
    [switch] $Release,
    [string] $TargetJava = "28"
)

$ErrorActionPreference = "Stop"

# Paths
$PROJECT_ROOT = if ($PSScriptRoot) { $PSScriptRoot } else { "A:/IdeaProjects/ZstdNetworkProject" }
$GRADLEW = Join-Path $PROJECT_ROOT "gradlew.bat"

function Write-Header {
    param([string] $Title)
    Write-Host "============================================" -ForegroundColor Cyan
    Write-Host " $Title" -ForegroundColor Yellow
    Write-Host "============================================" -ForegroundColor Cyan
    Write-Host ""
}

function Write-Status {
    param([string] $Message, [string] $Color = "White")
    Write-Host $Message -ForegroundColor $Color
}

function Write-Footer {
    param([int] $ExitCode, [string] $Message)
    Write-Host ""
    if ($ExitCode -eq 0) {
        Write-Host "============================================" -ForegroundColor Green
        Write-Host " OK: $Message" -ForegroundColor Green
        Write-Host "============================================" -ForegroundColor Green
    } else {
        Write-Host "============================================" -ForegroundColor Red
        Write-Host " FAILED: $Message" -ForegroundColor Red
        Write-Host "============================================" -ForegroundColor Red
    }
    exit $ExitCode
}

Write-Header "ZstdNetworkProject Build v0.3.1"

# Check the requested local JDK exists
$JavaHome = "C:\Program Files\Eclipse Adoptium\jdk-$TargetJava.0.0.14-hotspot"
if (-not (Test-Path (Join-Path $JavaHome "bin\java.exe"))) {
    Write-Status "ERROR: Java $TargetJava not found at $JavaHome" -ForegroundColor Red
    Write-Footer 1 "Java installation missing"
}
Write-Status "Using Java: $JavaHome" -ForegroundColor Green
Write-Status "Project root: $PROJECT_ROOT" -ForegroundColor Green

# Prefer the requested JDK for the Gradle daemon (the build itself forces toolchain 27).
$env:JAVA_HOME = $JavaHome

# Build tasks
$buildArgs = @("compileJava", "compileTestJava")
if ($Clean)   { $buildArgs = @("clean") + $buildArgs }
if ($Test)    { $buildArgs += "test" }
if ($Release) { $buildArgs += "build" }

Write-Status "Compiling source code..." -ForegroundColor Cyan

Push-Location $PROJECT_ROOT
try {
    & $GRADLEW @buildArgs
} finally {
    Pop-Location
}

if ($LASTEXITCODE -ne 0) {
    Write-Footer $LASTEXITCODE "Build process failed"
}

Write-Footer 0 "Build completed successfully!"