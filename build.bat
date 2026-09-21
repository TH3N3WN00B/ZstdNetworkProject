@echo off
REM Build script for ZstdNetworkProject
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-28.0.0.14-hotspot"
set "GRADLE_OPTS=-Dorg.gradle.java.home=%JAVA_HOME%"

echo ==============================================
echo ZstdNetworkProject - Build Beta 0.3.0
echo ==============================================
echo.
echo Using Java: %JAVA_HOME%
echo Project: %~dp0
echo.

%JAVA_HOME%\bin\java.exe -jar "%~dp0gradle\wrapper\gradle-wrapper.jar" %~dp0 clean compileJava compileTestJava test

echo.
if %ERRORLEVEL% EQU 0 (
    echo ==============================================
    echo BUILD SUCCESS!
    echo ==============================================
) else (
    echo ==============================================
    echo BUILD FAILED
    echo ==============================================
)
