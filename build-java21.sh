#!/usr/bin/env bash
# ZstdNetworkProject Build Script - Java 21 (Minecraft 1.21.x)
# Usa Velocity 3.5.0, Netty 4.1.110

set -euo pipefail

cd "$(dirname "$0")"

echo "=============================================="
echo "ZstdNetworkProject Build - Java 21"
echo "=============================================="

# Default versions (obfuscated era)
MOD_VERSION="${MOD_VERSION:-0.3.0}"
MINECRAFT_VERSION="${MINECRAFT_VERSION:-1.21.11}"

# Force Java 21
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"

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
echo "Build completed for Minecraft $MINECRAFT_VERSION (Java 21)"
echo "=============================================="
