#!/bin/sh
# Real runtime config-generation + launch for a Nectrix-hosted MT5/MT4
# terminal. Env vars here come straight from the per-account K8s Secret
# apps/mt-terminal-host/internal/k8sprovision builds (LOGIN/PASSWORD/SERVER/
# PAIRING_TOKEN/GATEWAY_HOST/GATEWAY_PORT/PLATFORM) — this script turns them
# into the .set (EA input parameters) and .ini (/config, for headless
# auto-login + auto-EA-attach) files a real MT5/MT4 terminal reads at
# startup, per MetaQuotes' own documented mechanism (AllowLiveTrading=1
# bypasses the manual "allow live trading" GUI confirmation dialog, required
# for headless operation — see apps/mt5-bridge-gateway/README.md's EA
# section for the EA source these input parameter names must match exactly:
# InpPairingToken/InpGatewayHost/InpGatewayPort/InpGatewayPath).
#
# UNVERIFIED end-to-end in this devcontainer (no Wine here — see this
# service's own README "Honest limitation" section): the .ini/.set key
# names and silent-launch flags below follow MetaQuotes' own documented
# /config mechanism, not independently confirmed against a real terminal
# boot.
set -eu

: "${PLATFORM:?PLATFORM is required (MT5 or MT4)}"
: "${LOGIN:?LOGIN is required}"
: "${PASSWORD:?PASSWORD is required}"
: "${SERVER:?SERVER is required}"
: "${PAIRING_TOKEN:?PAIRING_TOKEN is required}"
: "${GATEWAY_HOST:?GATEWAY_HOST is required}"
: "${GATEWAY_PORT:?GATEWAY_PORT is required}"

## WINEPREFIX reuses the exact prefix the terminal was installed into at
## build time (see terminal-image/Dockerfile's install-diagnostics stage)
## — carried forward into the final image since only diagnostics-export
## starts from `scratch`. Avoids any first-run Wine initialization surprise
## at container start that a fresh, never-before-used prefix could hit.
if [ "$PLATFORM" = "MT5" ]; then
  TERMINAL_DIR=/canonical-mt5
  export WINEPREFIX=/wine-mt5
  TERMINAL_EXE=terminal64.exe
  EA_NAME=NectrixBridgeMT5
elif [ "$PLATFORM" = "MT4" ]; then
  # Deliberately unsupported for now — real, live-verified finding: MQL4 has
  # no native Socket*() functions, so NectrixBridgeMT4.mq4 cannot compile as
  # designed (see terminal-image/Dockerfile's compile-ea stage comment and
  # apps/mt-terminal-host/README.md). No .ex4 is built into this image, so
  # failing fast and clearly here beats silently launching a terminal with
  # no EA ever able to attach.
  echo "entrypoint: PLATFORM=MT4 is not yet supported — MQL4 has no native socket support, so the EA bridge cannot run as designed; see apps/mt-terminal-host/README.md" >&2
  exit 1
else
  echo "entrypoint: unknown PLATFORM '$PLATFORM' (expected MT5 or MT4)" >&2
  exit 1
fi

SET_FILE="$TERMINAL_DIR/nectrix.set"
INI_FILE="$TERMINAL_DIR/nectrix.ini"

# EA input parameters — must match NectrixBridgeMT5.mq5/NectrixBridgeMT4.mq4's
# own `input` declarations exactly (InpPairingToken/InpGatewayHost/
# InpGatewayPort). InpGatewayPath is left at the EA's own default ("/ea/ws")
# since apps/mt5-bridge-gateway never varies it.
cat > "$SET_FILE" <<EOF
InpPairingToken=$PAIRING_TOKEN
InpGatewayHost=$GATEWAY_HOST
InpGatewayPort=$GATEWAY_PORT
EOF

cat > "$INI_FILE" <<EOF
[Common]
Login=$LOGIN
Password=$PASSWORD
Server=$SERVER

[Experts]
AllowLiveTrading=1
AllowDllImport=1
Enabled=1

[StartUp]
Expert=$EA_NAME
ExpertParameters=$SET_FILE
Symbol=EURUSD
Period=M1
EOF

# The EA itself owns the actual gateway connection/pairing (via its own
# OnInit/OnTimer, see the .mq5/.mq4 source) — this chart/symbol selection is
# only the terminal's own "a chart must exist to attach an EA to" mechanic,
# not otherwise meaningful (the EA doesn't trade EURUSD itself, it forwards
# whatever real trade-event/order-command traffic the gateway protocol
# carries for the platform account as a whole).
Xvfb :99 -screen 0 1024x768x16 &
export DISPLAY=:99

exec wine "$TERMINAL_DIR/$TERMINAL_EXE" /portable "/config:$INI_FILE"
