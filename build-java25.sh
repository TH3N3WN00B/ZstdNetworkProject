#!/usr/bin/env bash
# ZstdNetworkProject Build Script - Java 25 (Minecraft 26.x)
# Usa Velocity 4.2.1-SNAPSHOT, Netty 4.2.18

set -euo pipefail

cd "$(dirname "$0")"

echo "=============================================="
echo "ZstdNetworkProject Build - Java 25"
echo "=============================================="

# Default versions (unobfuscated era)
MOD_VERSION="${MOD_VERSION:-0.3.0}"
MINECRAFT_VERSION="${MINECRAFT_VERSION:-26.2}"

# Force Java 25
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot"

echo "Java Home: $JAVA_HOME"
echo "Minecraft Version: $MINECRAFT_VERSION"
echo "Mod Version: $MOD_VERSION"
echo ""

# Clean and build
./gradlew clean build --no-configuration-cache \
    -Pminecraft_version=$MINECRAFT_VERSION \
    --console=plain

echo ""
echo "=============================================="
echo "Build completed for Minecraft $MINECRAFT_VERSION (Java 25)"
echo "=============================================="
