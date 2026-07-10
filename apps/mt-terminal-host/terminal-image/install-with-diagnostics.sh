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
# Real, live-verified finding (confirmed against gmag11/MetaTrader5-Docker,
# a maintained real-world Wine+MT5 Docker project — see
# apps/mt-terminal-host/README.md): passing a custom /portable "/S:<dir>"
# destination to the installer is what was actually broken across three
# earlier CI attempts (xauth missing; installer exit code unreliable under
# Wine; a synthetic xdotool Enter keypress not reliably dismissing the
# final screen). The combination that real-world projects actually use is
# /auto ALONE — the installer places files at its own default location
# under WINEPREFIX, which this script discovers afterward via `find`
# rather than assuming a fixed path, then copies into a canonical
# directory the rest of the Dockerfile can rely on unconditionally.
set -u

INSTALLER="$1"
WINE_PREFIX_DIR="$2"
LABEL="$3"
TERMINAL_EXE_NAME="$4" # e.g. terminal64.exe or terminal.exe
EDITOR_EXE_NAME="$5"   # e.g. metaeditor64.exe or metaeditor.exe
CANONICAL_DIR="$6"

mkdir -p /diagnostics "$WINE_PREFIX_DIR" "$CANONICAL_DIR"
export WINEPREFIX="$WINE_PREFIX_DIR"

Xvfb :99 -screen 0 1024x768x16 &
XVFB_PID=$!
export DISPLAY=:99
sleep 2

wine "$INSTALLER" /auto \
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

# Discover where the installer actually put things — a real signal, not a
# path guess (see this script's own header note above). Copies the WHOLE
# containing directory (not just the two named exes) into CANONICAL_DIR,
# since the terminal/MetaEditor need their surrounding DLLs/resources to
# actually run, not just the two binaries themselves.
TERMINAL_PATH=$(find "$WINE_PREFIX_DIR" -iname "$TERMINAL_EXE_NAME" 2>/dev/null | head -1)
EDITOR_PATH=$(find "$WINE_PREFIX_DIR" -iname "$EDITOR_EXE_NAME" 2>/dev/null | head -1)
echo "$TERMINAL_PATH" >"/diagnostics/${LABEL}.terminal-path"
echo "$EDITOR_PATH" >"/diagnostics/${LABEL}.editor-path"

if [ -n "$TERMINAL_PATH" ]; then
  cp -r "$(dirname "$TERMINAL_PATH")"/. "$CANONICAL_DIR"/ 2>/dev/null || true
  # The installer's on-disk casing isn't guaranteed (confirmed empirically:
  # a real MT5 install produced "MetaEditor64.exe", not "metaeditor64.exe"),
  # so re-copy onto the exact fixed-case names the rest of the Dockerfile
  # relies on, rather than assuming the whole-directory cp above already
  # matches — Linux filesystems are case-sensitive, unlike Windows.
  cp "$TERMINAL_PATH" "$CANONICAL_DIR/$TERMINAL_EXE_NAME" 2>/dev/null || true
fi
if [ -n "$EDITOR_PATH" ]; then
  cp "$EDITOR_PATH" "$CANONICAL_DIR/$EDITOR_EXE_NAME" 2>/dev/null || true
fi

echo "install-with-diagnostics: $LABEL exited $EXIT_CODE, terminal=[$TERMINAL_PATH], editor=[$EDITOR_PATH]" >&2
exit 0
