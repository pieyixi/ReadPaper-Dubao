#!/usr/bin/env bash
# 只在 Codex Cloud Linux 环境准备工具；不要在用户 Windows 机器运行。
set -euo pipefail
if [[ "$(uname -s)" != "Linux" ]]; then
  echo 'This setup script requires a Linux cloud environment.' >&2
  exit 1
fi

PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
API_LEVEL="${ANDROID_API_LEVEL:-34}"
BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-34.0.0}"
COMMAND_TOOLS_VERSION=11076708

if ! command -v java >/dev/null || [[ "$(java -version 2>&1)" != *'version "17.'* ]] || ! command -v unzip >/dev/null || ! command -v curl >/dev/null; then
  if [[ "$(id -u)" == 0 ]]; then
    apt-get update
    apt-get install -y --no-install-recommends openjdk-17-jdk-headless unzip curl ca-certificates
  elif command -v sudo >/dev/null; then
    sudo apt-get update
    sudo apt-get install -y --no-install-recommends openjdk-17-jdk-headless unzip curl ca-certificates
  else
    echo 'Install JDK 17, unzip and curl in this cloud environment first.' >&2
    exit 1
  fi
fi

if [[ -d /usr/lib/jvm/java-17-openjdk-amd64 ]]; then
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
elif [[ -d /usr/lib/jvm/java-17-openjdk-arm64 ]]; then
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
else
  export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
fi
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.local/share/dubao/android-sdk}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.cache/dubao/gradle}"
mkdir -p "$ANDROID_HOME" "$GRADLE_USER_HOME"

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$SDKMANAGER" ]]; then
  SETUP_TEMP="$(mktemp -d)"
  trap 'rm -rf -- "$SETUP_TEMP"' EXIT
  curl --fail --location --retry 3 --connect-timeout 30 --max-time 300 \
    "https://dl.google.com/android/repository/commandlinetools-linux-${COMMAND_TOOLS_VERSION}_latest.zip" \
    -o "$SETUP_TEMP/tools.zip"
  unzip -q "$SETUP_TEMP/tools.zip" -d "$SETUP_TEMP/unpacked"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  mv "$SETUP_TEMP/unpacked/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi

# yes 可能因管道关闭返回 141；仅以 sdkmanager 的退出码判断许可证流程。
set +e
yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null
LICENSE_EXIT=${PIPESTATUS[1]}
set -e
if [[ "$LICENSE_EXIT" != 0 ]]; then
  echo 'Android SDK license setup failed.' >&2
  exit "$LICENSE_EXIT"
fi
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" \
  'platform-tools' "platforms;android-$API_LEVEL" "build-tools;$BUILD_TOOLS"

cd "$PROJECT_ROOT"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
mkdir -p .cloud
{
  printf 'export JAVA_HOME=%q\n' "$JAVA_HOME"
  printf 'export ANDROID_HOME=%q\n' "$ANDROID_HOME"
  printf 'export ANDROID_SDK_ROOT=%q\n' "$ANDROID_HOME"
  printf 'export GRADLE_USER_HOME=%q\n' "$GRADLE_USER_HOME"
  printf 'export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"\n'
} > .cloud/environment.sh
chmod +x gradlew scripts/cloud-setup.sh scripts/cloud-build.sh
bash ./gradlew --version
