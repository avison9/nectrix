#!/bin/bash
# Runs a Windows installer under Wine + Xvfb, capturing periodic screenshots
# and the real exit code into /diagnostics — used ONLY to diagnose why an
# install silently fails (a GUI installer's own error dialog is otherwise
# invisible to a text-only Docker build log). The actual pass/fail gate for
# the real build lives in a LATER, separate Dockerfile step (see
# install-verified in the Dockerfile) — this script always exits 0
# regardless of whether the underlying install succeeded, so this stage's
# own layer always commits and its /diagnostics content stays exportable
# via `docker buildx build --target diagnostics-export -o type=local,...`
# even when the real build fails downstream.
set -u

INSTALLER="$1"
DEST_DIR="$2"
LABEL="$3"

mkdir -p /diagnostics

Xvfb :99 -screen 0 1024x768x16 &
XVFB_PID=$!
export DISPLAY=:99
sleep 2

# +seh,+module: exception-handling and DLL-load tracing — the two channels
# most likely to explain a silent exit(1) with no installer-drawn error
# text (a crash before any dialog renders, or a missing/incompatible DLL).
WINEDEBUG=+seh,+module wine "$INSTALLER" /auto /portable "/S:$DEST_DIR" \
  > "/diagnostics/${LABEL}.stdout.log" 2> "/diagnostics/${LABEL}.stderr.log" &
WINE_PID=$!

i=0
while kill -0 "$WINE_PID" 2>/dev/null && [ "$i" -lt 20 ]; do
  xwd -root -display :99 -out "/diagnostics/${LABEL}-${i}.xwd" 2>/dev/null || true
  sleep 3
  i=$((i + 1))
done

wait "$WINE_PID"
EXIT_CODE=$?
echo "$EXIT_CODE" >"/diagnostics/${LABEL}.exitcode"

# One final screenshot right after exit, before Xvfb is killed — the most
# likely frame to show an installer error dialog, if one was ever drawn.
xwd -root -display :99 -out "/diagnostics/${LABEL}-final.xwd" 2>/dev/null || true

kill "$XVFB_PID" 2>/dev/null || true
wait "$XVFB_PID" 2>/dev/null || true

for f in "/diagnostics/${LABEL}"*.xwd; do
  [ -f "$f" ] && convert "$f" "${f%.xwd}.png" 2>/dev/null && rm -f "$f"
done

echo "install-with-diagnostics: $LABEL exited $EXIT_CODE" >&2
exit 0
