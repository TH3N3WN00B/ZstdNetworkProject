# ZstdNetworkProject - Beta 0.3.0 Build Script
# Compiles and tests the entire project using JDK 28

param(
    [switch] $Clean,
    [switch] $Test,
    [switch] $Release,
    [string] $TargetJava = "28"
)

$ErrorActionPreference = "Stop"

# Paths
$PROJECT_ROOT = "A:/IdeaProjects/ZstdNetworkProject"
$BUILD_PYTHON = "$PROJECT_ROOT/build_beta030.py"
$GRADLE_OPTS = "-Dorg.gradle.java.home=C:\Program Files\Eclipse Adoptium\jdk-$TargetJava.0.0.14-hotspot"

function Write-Header {
    param(string $Title)
    Write-Host "============================================" -ForegroundColor Cyan
    Write-Host " $Title" -ForegroundColor Yellow
    Write-Host "============================================" -ForegroundColor Cyan
    Write-Host ""
}

function Write-Status {
    param(string $Message, [string] $Color = "White")
    Write-Host "[$Color] $Message" -ForegroundColor $Color -NoNewline
}

function Write-Footer {
    param(int $ExitCode, [string] $Message)
    Write-Host "" -NoNewline
    if ($ExitCode -eq 0) {
        Write-Host "============================================" -ForegroundColor Green
        Write-Host " ✅ SUCCESS" -ForegroundColor Green
        Write-Host "============================================" -ForegroundColor Green
    } else {
        Write-Host "============================================" -ForegroundColor Red
        Write-Host " ❌ FAILED" -ForegroundColor Red
        Write-Host "============================================" -ForegroundColor Red
    }
    Write-Host ""
    exit $ExitCode
}

Write-Header "ZstdNetworkProject Build v0.3.0"

# Verify Python script exists
if (-not (Test-Path $BUILD_PYTHON)) {
    Write-Status "ERROR: Build script not found at $BUILD_PYTHON" -ForegroundColor Red
    Write-Footer 1 "Missing build automation script"
}

# Check Java installation
$JavaHome = "C:\Program Files\Eclipse Adoptium\jdk-$TargetJava.0.0.14-hotspot"
if (-not (Test-Path "$JavaHome\bin\java.exe")) {
    Write-Status "ERROR: Java $TargetHome not found at $JavaHome" -ForegroundColor Red
    Write-Footer 1 "Java installation missing"
}

Write-Status "Using Java $TargetHome" -ForegroundColor Green
Write-Status "Project root: $PROJECT_ROOT" -ForegroundColor Green

# Clean if requested
if ($Clean) {
    Write-Status "Cleaning build artifacts..." -ForegroundColor Cyan
    $gradleClean = "$PROJECT_ROOT\gradlew.bat clean"
    if (Test-Path $gradleClean) {
        & $gradleClean /p $PROJECT_ROOT
    }
}

# Compile source code
Write-Status "Compiling source code..." -ForegroundColor Cyan
$gradleBuild = "$PROJECT_ROOT\gradlew.bat"
$buildArgs = @(
    "clean",
    "compileJava",
    "compileTestJava"
)
if ($Test) {
    $buildArgs += "test"
}
if ($Release) {
    $buildArgs += "build"
}

& $gradleBuild @buildArgs /p $PROJECT_ROOT

$LASTEXITCODE

if ($LASTEXITCODE -ne 0) {
    Write-Status "Gradle build failed with exit code $LASTEXITCODE" -ForegroundColor Red
    Write-Footer $LASTEXITCODE "Build process failed"
}

Write-Footer 0 "Build completed successfully!"
