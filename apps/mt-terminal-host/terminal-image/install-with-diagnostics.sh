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
#
# Real, live-verified finding (see the mt-terminal-install-diagnostics CI
# artifact from run 29115696730): MetaQuotes' /auto silent-install flag does
# NOT suppress the installer's own final "Finish"/"Congratulations" screen —
# both MT5 and MT4 genuinely complete their install (file-copying and all)
# and then just sit at that confirmation screen forever, which is why the
# wrapped process previously never exited cleanly. This script now
# periodically sends Enter (Finish is the wizard's default/focused button)
# to dismiss it — a harmless no-op on every earlier screen, since nothing is
# focused/actionable yet.
set -u

INSTALLER="$1"
DEST_DIR="$2"
LABEL="$3"

mkdir -p /diagnostics

Xvfb :99 -screen 0 1024x768x16 &
XVFB_PID=$!
export DISPLAY=:99
sleep 2

wine "$INSTALLER" /auto /portable "/S:$DEST_DIR" \
  > "/diagnostics/${LABEL}.stdout.log" 2> "/diagnostics/${LABEL}.stderr.log" &
WINE_PID=$!

i=0
while kill -0 "$WINE_PID" 2>/dev/null && [ "$i" -lt 20 ]; do
  xwd -root -display :99 -out "/diagnostics/${LABEL}-${i}.xwd" 2>/dev/null || true
  xdotool key --clearmodifiers Return 2>/dev/null || true
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
