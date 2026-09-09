#!/usr/bin/env bash
# Tail the on-device engine logs and the :llm process footprint.
set -uo pipefail
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
PKG=com.aiassistant
if command -v adb >/dev/null 2>&1; then ADB=adb
else ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"; fi
[ -n "${ADB_SERIAL:-}" ] && ADB="$ADB -s $ADB_SERIAL"

case "${1:-log}" in
  log)
    $ADB logcat -c
    $ADB logcat OnDeviceLlmEngine:V LlmService:V LlmClient:V litert:V \
      AndroidRuntime:E lowmemorykiller:V ActivityManager:W '*:S'
    ;;
  mem)
    # RSS of the inference process -- the number that tells us whether a
    # smaller contextTokens actually shrinks the KV allocation.
    $ADB shell dumpsys meminfo "$PKG:llm" | sed -n '1,25p'
    ;;
  kills)
    $ADB shell dumpsys activity lru | grep -i "$PKG" || true
    $ADB logcat -d | grep -iE "lmk|kill.*$PKG|died" | tail -30
    ;;
  clearkv)
    $ADB shell run-as "$PKG" rm -f "/data/data/$PKG/shared_prefs/llm_kv_capacity.xml" &&
      echo "learned KV capacities cleared"
    ;;
  *) echo "usage: watch.sh [log|mem|kills|clearkv]" >&2; exit 1 ;;
esac
