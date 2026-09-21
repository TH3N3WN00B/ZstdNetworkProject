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

# major version of the JVM that gradle will run on (JAVA_HOME first, PATH fallback)
java_bin="java"
if [ -n "${JAVA_HOME:-}" ]; then
  java_bin="$JAVA_HOME/bin/java"
fi
java_major() {
  local out
  out="$("$java_bin" -version 2>&1 | head -n1)"
  if [[ "$out" =~ \"1\.([0-9]+) ]]; then
    echo "${BASH_REMATCH[1]}"
  elif [[ "$out" =~ \"([0-9]+) ]]; then
    echo "${BASH_REMATCH[1]}"
  else
    echo "0"
  fi
}

# The whole build needs a Gradle daemon on Java 25+: fabric-loom 1.18.1, the moddev
# plugin and Velocity 4.2 all require it, and the 26.x era targets 25. The obfuscated
# group's Java-21 bytecode target is handled by the toolchain, never by the daemon.
if [ "$(java_major)" -lt 25 ]; then
  echo "ABORT: this build requires a Java 25+ JVM (JAVA_HOME currently resolves to Java $(java_major))." >&2
  exit 1
fi

dist="$(pwd)/dist"
mkdir -p "$dist"
failed=()

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

mod_version="$(grep '^mod_version=' gradle.properties | cut -d= -f2 | tr -d ' \r\n')"

for version in "${versions[@]}"; do
  props="gradle-mc${version}.properties"
  if [ ! -f "$props" ]; then
    echo "SKIP: no $props" >&2
    continue
  fi

  # Parse the group file into property args.
gradle_args=()
  neoforge_version=""
  fabric_version=""
  paper_version=""
  while IFS= read -r line; do
    if [[ "$line" =~ ^[[:space:]]*([A-Za-z0-9_.-]+)[[:space:]]*=[[:space:]]*(.+)[[:space:]]*$ ]]; then
      key="${BASH_REMATCH[1]}"
      val="${BASH_REMATCH[2]}"
      val="${val%$'\r'}"  # group files can be CRLF; drop the trailing carriage return
      gradle_args+=("-P${key}=${val}")
      case "$key" in
        neoforge_version) neoforge_version="$val" ;;
        fabric_api_version) fabric_version="$val" ;;
        paper_version) paper_version="$val" ;;
      esac
    fi
  done < "$props"

  # Each module is only built when the group defines its version (not NONE):
  # a group like 26.3 that has paper but no fabric/neoforge release yet builds
  # paper only, and settings.gradle leaves the other modules out of the build.
  tasks=()
  if [ -n "$neoforge_version" ] && [ "$neoforge_version" != "NONE" ]; then
    tasks+=(:neoforge:build)
  fi
  if [ -n "$fabric_version" ] && [ "$fabric_version" != "NONE" ]; then
    tasks+=(:fabric:build)
  fi
  if [ -n "$paper_version" ] && [ "$paper_version" != "NONE" ]; then
    tasks+=(:paper:build)
  fi

  echo "=== Building Minecraft $version ==="
  # A failing version group must not abort the remaining groups (nor the Velocity
  # build at the end); record it and keep going, then exit non-zero at the very end.
  # This mirrors build-all.ps1, which already continues past a failed group.
  if ! ./gradlew "${tasks[@]}" "${gradle_args[@]}" --console=plain; then
    echo "BUILD FAILED for $version" >&2
    failed+=("$version")
    continue
  fi

  for module in neoforge fabric paper; do
    case "$module" in
      neoforge) module_version="$neoforge_version" ;;
      fabric) module_version="$fabric_version" ;;
      paper) module_version="$paper_version" ;;
    esac
    if [ -z "$module_version" ] || [ "$module_version" = "NONE" ]; then
      continue
    fi
    find "$module/build/libs" -maxdepth 1 -name '*.jar' \
      ! -name '*sources*' ! -name '*javadoc*' ! -name '*dev*' \
      -name "*-$mod_version-mc${version}.jar" \
      -exec cp {} "$dist" \;
  done
done

echo '=== Building Velocity ==='
if ./gradlew :velocity:build --console=plain; then
  find velocity/build/libs -maxdepth 1 -name '*.jar' \
    ! -name '*sources*' ! -name '*javadoc*' ! -name '*dev*' \
    -name "*-${mod_version}-mc*.jar" \
    -exec cp {} "$dist" \;
else
  echo "BUILD FAILED for velocity" >&2
  failed+=("velocity")
fi

echo "Artifacts in: $dist"
ls -1 "$dist"

if [ "${#failed[@]}" -gt 0 ]; then
  echo "FAILED targets: ${failed[*]}" >&2
  exit 1
fi
