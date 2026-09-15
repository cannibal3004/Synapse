#!/usr/bin/env bash
# Push a .litertlm bundle into Synapse's internal model directory.
#
#   ./sideload.sh /path/to/model.litertlm [dest-name.litertlm]
#
# Defaults the destination name to spark-1.7b-int4-64k.litertlm. Any sidecar
# (.json / .jinja) sitting beside this script under the destination's base name
# is pushed too. Set ADB_SERIAL to pick a device when more than one is attached.
set -euo pipefail

# Git Bash rewrites /data/... into a Windows path before adb ever sees it, so
# device-side paths come back as "C:/Program Files/Git/data/...". Turning the
# conversion off fixes those, but then the *local* file argument also has to be
# a Windows path, because adb.exe is a Windows binary. cygpath handles that.
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
winpath() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

PKG=com.aiassistant
DEST_DIR="/data/data/$PKG/files/litertlm_models"
SRC="${1:?usage: sideload.sh <model.litertlm> [dest-name]}"
DEST_NAME="${2:-spark-1.7b-int4-64k.litertlm}"
BASE="${DEST_NAME%.*}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --- locate adb --------------------------------------------------------------
if command -v adb >/dev/null 2>&1; then
  ADB=adb
elif [ -x "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" ]; then
  ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
else
  echo "adb not found. Set it on PATH or install platform-tools." >&2
  exit 1
fi
if [ -n "${ADB_SERIAL:-}" ]; then
  ADB="$ADB -s $ADB_SERIAL"
fi

run() { $ADB shell "$@"; }
asapp() { run run-as "$PKG" "$@"; }

echo "== device"
$ADB wait-for-device
run getprop ro.product.model

echo "== space on /data (need ~2x $(du -h "$SRC" | cut -f1) for staging)"
run df -h /data

echo "== push $DEST_NAME"
$ADB push "$(winpath "$SRC")" "/data/local/tmp/$DEST_NAME"
run chmod 644 "/data/local/tmp/$DEST_NAME"
asapp mkdir -p "$DEST_DIR"

# run-as cannot always read /data/local/tmp; fall back to letting the shell
# read the file and the app write it.
if ! asapp cp "/data/local/tmp/$DEST_NAME" "$DEST_DIR/"; then
  echo "   cp refused, streaming instead"
  run "cat /data/local/tmp/$DEST_NAME | run-as $PKG sh -c 'cat > $DEST_DIR/$DEST_NAME'"
fi
run rm -f "/data/local/tmp/$DEST_NAME"

# --- sidecars ----------------------------------------------------------------
for ext in json jinja; do
  f="$HERE/$BASE.$ext"
  [ -f "$f" ] || continue
  echo "== push $BASE.$ext"
  $ADB push "$(winpath "$f")" "/data/local/tmp/$BASE.$ext"
  run chmod 644 "/data/local/tmp/$BASE.$ext"
  asapp cp "/data/local/tmp/$BASE.$ext" "$DEST_DIR/" ||
    run "cat /data/local/tmp/$BASE.$ext | run-as $PKG sh -c 'cat > $DEST_DIR/$BASE.$ext'"
  run rm -f "/data/local/tmp/$BASE.$ext"
done

echo "== installed"
asapp ls -l "$DEST_DIR"

cat <<EOF

Next:
  1. Settings -> Model Name: $DEST_NAME
  2. Force-stop the app so the :llm process restarts:
       $ADB shell am force-stop $PKG
  3. Watch the first init (the repack takes a while):
       ./watch.sh
EOF
