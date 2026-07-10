#!/bin/bash
# Compiles one MQL EA via headless MetaEditor under Wine, using the same
# manually managed Xvfb (start, sleep, run, kill) as
# install-with-diagnostics.sh — not `xvfb-run -a`, which failed near-
# instantly here ("X connection to :99 broken") the one time it was tried;
# this manual pattern is the only one actually proven reliable in this
# Dockerfile, across two successful terminal installs.
set -u

WINE_PREFIX_DIR="$1"
EDITOR_PATH="$2"
SOURCE_PATH="$3"
COMPILED_PATH="$4" # expected .ex5/.ex4 output path — the real pass/fail signal

export WINEPREFIX="$WINE_PREFIX_DIR"

Xvfb :99 -screen 0 1024x768x16 &
XVFB_PID=$!
export DISPLAY=:99
sleep 2

wine "$EDITOR_PATH" "/compile:$SOURCE_PATH" /log
EXIT_CODE=$?

kill "$XVFB_PID" 2>/dev/null || true
wait "$XVFB_PID" 2>/dev/null || true

# MetaEditor's own compile log — same basename as the source, .log
# extension — printed directly into the build log so a real compile error
# is visible without a separate diagnostics-export step.
LOG_PATH="${SOURCE_PATH%.*}.log"
if [ -f "$LOG_PATH" ]; then
  echo "=== MetaEditor compile log ($LOG_PATH) ===" >&2
  cat "$LOG_PATH" >&2
fi

if [ ! -f "$COMPILED_PATH" ]; then
  echo "compile-ea: $COMPILED_PATH was not produced (wine exit code: $EXIT_CODE)" >&2
  exit 1
fi

echo "compile-ea: $COMPILED_PATH compiled successfully" >&2
exit 0
