#!/usr/bin/env bash
# ZstdNetworkProject Build Script - All Platforms
# Compiles for Java 21/27, Netty 4.2.18, Velocity 4.2.1-SNAPSHOT

set -euo pipefail

cd "$(dirname "$0")"

# Default versions
MOD_VERSION="${MOD_VERSION:-0.3.1}"
BUILD_VERSION="${BUILD_VERSION:-beta-0.3.1}"
JAVA_HOME="${JAVA_HOME:-C:\Program Files\Eclipse Adoptium\jdk-27}"

echo "=============================================="
echo "ZstdNetworkProject Multi-Platform Build"
echo "=============================================="
echo "Java Home: $JAVA_HOME"
echo "Mod Version: $MOD_VERSION"
echo "Build Version: $BUILD_VERSION"
echo ""

export JAVA_HOME

# Clean all modules
echo "🧹 Cleaning all modules..."
./gradlew clean --no-configuration-cache --console=plain

# Build all modules
echo ""
echo "📦 Building all modules..."
./gradlew build --no-configuration-cache --console=plain -x test

# Copy JARs to dist directory
echo ""
echo "📁 Copying JARs to dist directory..."
./gradlew copyToDist --no-configuration-cache --console=plain

echo ""
echo "=============================================="
echo "✅ Build completed!"
echo "=============================================="
echo ""
echo "📦 Output files in: $(pwd)/dist/"
echo ""
echo "📝 List of compiled JARs:"
ls -lh dist/*.jar 2>/dev/null || echo "No JARs found in dist/"
