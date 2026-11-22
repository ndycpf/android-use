#!/usr/bin/env bash
set -euo pipefail

ANDROID_SDK_DIR="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_HOME="$ANDROID_SDK_DIR"
export ANDROID_SDK_ROOT="$ANDROID_SDK_DIR"

if [ -z "${JAVA_HOME:-}" ] && [ -d "/usr/lib/jvm/java-21-openjdk-amd64" ]; then
  export JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
fi

export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# Ensure non-interactive bash shells (via BASH_ENV=~/.bashrc) pick up exports.
touch "$HOME/.bashrc"
sed -i '/case \$- in/,/esac/d' "$HOME/.bashrc" 2>/dev/null || true

append_bashrc_once() {
  local line="$1"
  grep -qxF "$line" "$HOME/.bashrc" || echo "$line" >> "$HOME/.bashrc"
}

append_bashrc_once "export ANDROID_HOME=$ANDROID_HOME"
append_bashrc_once "export ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"
if [ -n "${JAVA_HOME:-}" ]; then
  append_bashrc_once "export JAVA_HOME=$JAVA_HOME"
fi
append_bashrc_once 'export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"'

# Install any missing Android SDK packages required for Kotlin/Android builds.
if command -v sdkmanager >/dev/null 2>&1; then
  missing_pkgs=()
  [ -d "$ANDROID_HOME/platform-tools" ] || missing_pkgs+=("platform-tools")
  [ -d "$ANDROID_HOME/platforms/android-35" ] || missing_pkgs+=("platforms;android-35")
  [ -d "$ANDROID_HOME/platforms/android-36" ] || missing_pkgs+=("platforms;android-36")
  [ -d "$ANDROID_HOME/build-tools/35.0.0" ] || missing_pkgs+=("build-tools;35.0.0")
  [ -d "$ANDROID_HOME/build-tools/36.0.0" ] || missing_pkgs+=("build-tools;36.0.0")

  if [ "${#missing_pkgs[@]}" -gt 0 ]; then
    yes | sdkmanager --licenses >/dev/null 2>&1 || true
    sdkmanager --install "${missing_pkgs[@]}"
  fi
fi

# Generate local.properties only when a Gradle project exists and ignores it.
if { [ -f "settings.gradle.kts" ] || [ -f "settings.gradle" ]; } && [ ! -f "local.properties" ]; then
  if git check-ignore -q local.properties 2>/dev/null; then
    printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
  fi
fi

# Bootstrap Gradle wrapper when present.
if [ -f "./gradlew" ]; then
  chmod +x ./gradlew
  ./gradlew --no-daemon --version >/dev/null
fi

# Quick toolchain verification.
java -version 2>&1 | head -n 1
adb version | head -n 1
if [ -f "./gradlew" ]; then
  ./gradlew --no-daemon --version | grep -E '^Gradle '
elif command -v gradle >/dev/null 2>&1; then
  gradle --version | grep -E '^Gradle '
fi
