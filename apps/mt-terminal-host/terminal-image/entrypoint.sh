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
  # TICKET-121: re-enabled. NectrixBridgeMT4.mq4 was rewritten to speak HTTP
  # long-polling via MQL4's native WebRequest() (MQL4 has no native
  # Socket*() functions, so the original WebSocket-based design could never
  # compile — see terminal-image/Dockerfile's compile-ea stage comment and
  # that EA's own header). WebRequest() needs its target URL allow-listed —
  # see the [Experts] WebRequestUrls attempt below.
  TERMINAL_DIR=/canonical-mt4
  export WINEPREFIX=/wine-mt4
  TERMINAL_EXE=terminal.exe
  EA_NAME=NectrixBridgeMT4
else
  echo "entrypoint: unknown PLATFORM '$PLATFORM' (expected MT5 or MT4)" >&2
  exit 1
fi

SET_FILE="$TERMINAL_DIR/nectrix.set"
INI_FILE="$TERMINAL_DIR/nectrix.ini"

# EA input parameters — must match NectrixBridgeMT5.mq5/NectrixBridgeMT4.mq4's
# own `input` declarations exactly (InpPairingToken/InpGatewayHost/
# InpGatewayPort). InpGatewayPath is left at each EA's own platform-specific
# default ("/ea/ws" for MT5's WebSocket, "/ea" for MT4's HTTP long-polling)
# since apps/mt5-bridge-gateway never varies it per account.
cat > "$SET_FILE" <<EOF
InpPairingToken=$PAIRING_TOKEN
InpGatewayHost=$GATEWAY_HOST
InpGatewayPort=$GATEWAY_PORT
EOF

# GATEWAY_ALLOW_URL is what MT4's WebRequest() calls need allow-listed —
# MetaQuotes' only DOCUMENTED mechanism for this is the GUI (Tools > Options
# > Expert Advisors > "Allow WebRequest for listed URL"), so
# WebRequestUrls below is a best-effort headless attempt, NOT a confirmed
# working mechanism — UNVERIFIED end-to-end in this devcontainer (no Wine
# here, same standing limitation this script's own header already
# documents for the rest of its .ini generation). If it turns out not to be
# honored by a real terminal, the fallback is either a one-time manual GUI
# step baked into the account-image at provisioning time, or a documented
# registry-based equivalent — neither investigated yet.
GATEWAY_ALLOW_URL="http://$GATEWAY_HOST:$GATEWAY_PORT"

cat > "$INI_FILE" <<EOF
[Common]
Login=$LOGIN
Password=$PASSWORD
Server=$SERVER

[Experts]
AllowLiveTrading=1
AllowDllImport=1
Enabled=1
WebRequestUrls=$GATEWAY_ALLOW_URL

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
