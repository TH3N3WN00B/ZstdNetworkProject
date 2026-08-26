#!/usr/bin/env bash
# Builds every Minecraft version group (gradle-mc<version>.properties) plus the
# version-independent Velocity plugin, and collects all jars into dist/.
#
# For each version group it builds neoforge and fabric; the paper module is only
# built when the group defines a real paper_version (not NONE).
#
# Usage (Git Bash):
#   ./build-all.sh               # build all version groups
#   ./build-all.sh 1.21.4 26.2   # build specific versions
set -euo pipefail
cd "$(dirname "$0")"

dist="$(pwd)/dist"
mkdir -p "$dist"

# Clean up obsolete jars and old build logs
rm -f "$dist"/*.jar
rm -f build-*.log

if [ "$#" -eq 0 ]; then
  versions=()
  for f in gradle-mc*.properties; do
    v="${f#gradle-mc}"
    versions+=("${v%.properties}")
  done
else
  versions=("$@")
fi

for version in "${versions[@]}"; do
  props="gradle-mc${version}.properties"
  if [ ! -f "$props" ]; then
    echo "SKIP: no $props" >&2
    continue
  fi

  # Parse the group file into property args.
  gradle_args=()
  paper_version=""
  while IFS= read -r line; do
    if [[ "$line" =~ ^[[:space:]]*([A-Za-z0-9_.-]+)[[:space:]]*=[[:space:]]*(.+)[[:space:]]*$ ]]; then
      key="${BASH_REMATCH[1]}"
      val="${BASH_REMATCH[2]}"
      gradle_args+=("-P${key}=${val}")
      if [ "$key" = "paper_version" ]; then
        paper_version="$val"
      fi
    fi
  done < "$props"

  tasks=(:neoforge:build :fabric:build)
  if [ -n "$paper_version" ] && [ "$paper_version" != "NONE" ]; then
    tasks+=(:paper:build)
  fi

  echo "=== Building Minecraft $version ==="
  ./gradlew "${tasks[@]}" "${gradle_args[@]}"

  for module in neoforge fabric paper; do
    if [ "$module" = "paper" ] && { [ -z "$paper_version" ] || [ "$paper_version" = "NONE" ]; }; then
      continue
    fi
    find "$module/build/libs" -maxdepth 1 -name '*.jar' \
      ! -name '*sources*' ! -name '*javadoc*' ! -name '*dev*' \
      -name "*mc${version}.jar" \
      -exec cp {} "$dist" \;
  done
done

echo '=== Building Velocity ==='
./gradlew :velocity:build --console=plain
find velocity/build/libs -maxdepth 1 -name '*.jar' \
  ! -name '*sources*' ! -name '*javadoc*' ! -name '*dev*' \
  -exec cp {} "$dist" \;

echo "Artifacts in: $dist"
ls -1 "$dist"
