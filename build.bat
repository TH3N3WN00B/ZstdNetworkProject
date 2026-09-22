@echo off
REM Build script for ZstdNetworkProject
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-28.0.0.14-hotspot"

echo ==============================================
echo ZstdNetworkProject - Build Beta 0.3.1
echo ==============================================
echo.
echo Using Java: %JAVA_HOME%
echo Project: %~dp0
echo.

pushd "%~dp0"
call gradlew.bat clean compileJava compileTestJava test
set "GRADLE_EXIT=%ERRORLEVEL%"
popd

echo.
if %GRADLE_EXIT% EQU 0 (
    echo ==============================================
    echo BUILD SUCCESS!
    echo ==============================================
) else (
    echo ==============================================
    echo BUILD FAILED
    echo ==============================================
)
