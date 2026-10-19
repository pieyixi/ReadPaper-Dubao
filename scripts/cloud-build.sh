#!/usr/bin/env bash
# 配置与构建均在云端进行，签名文件不进入 Git 或产物目录。
set -euo pipefail
if [[ "$(uname -s)" != "Linux" ]]; then
  echo 'Run Android builds in the configured Linux cloud environment.' >&2
  exit 1
fi
PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"
if [[ -f .cloud/environment.sh ]]; then
  source .cloud/environment.sh
fi
MODE="${1:-debug}"
case "$MODE" in
  debug) TASKS=(assembleDebug) ;;
  checks) TASKS=(testDebugUnitTest lintDebug) ;;
  release)
    if [[ -n "${DUBAO_KEYSTORE_BASE64:-}" && -z "${DUBAO_KEYSTORE_FILE:-}" ]]; then
      SIGNING_TEMP="$(mktemp -d)"
      trap 'rm -rf -- "$SIGNING_TEMP"' EXIT
      umask 077
      printf '%s' "$DUBAO_KEYSTORE_BASE64" | base64 --decode > "$SIGNING_TEMP/release.jks"
      export DUBAO_KEYSTORE_FILE="$SIGNING_TEMP/release.jks"
    fi
    : "${DUBAO_STORE_PASSWORD:?Original Release signing password is required}"
    : "${DUBAO_KEY_PASSWORD:?Original Release key password is required}"
    : "${DUBAO_KEY_ALIAS:?Original Release key alias is required}"
    : "${DUBAO_KEYSTORE_FILE:?Original Release keystore file is required}"
    [[ -f "$DUBAO_KEYSTORE_FILE" ]] || { echo 'Release keystore file not found.' >&2; exit 1; }
    TASKS=(assembleRelease lintRelease)
    ;;
  *) echo 'Usage: bash scripts/cloud-build.sh [debug|checks|release]' >&2; exit 2 ;;
esac
bash ./gradlew --no-daemon --console=plain "${TASKS[@]}"
