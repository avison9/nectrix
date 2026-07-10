# Build context MUST be the repo root:
#   docker build -f apps/mt-terminal-host/terminal-image/Dockerfile -t mt-terminal-image .
#
# Nectrix-hosted MT5/MT4 terminals: Wine + Xvfb + a real MetaTrader terminal
# + our own EA, compiled INSIDE this same build (a real MT5/MT4 terminal
# install always bundles MetaEditor, so there's no need for a separate
# compile step/image elsewhere — one build, one source of truth). See
# entrypoint.sh for the runtime config-generation + launch sequence, and
# apps/mt-terminal-host/README.md's "Real, in-progress terminal-image
# debugging" section for the full history of what's been found/fixed here
# so far via real CI runs. Wired into CI as a workflow_dispatch-only job
# (build-terminal-image.yml), not on every push, for exactly that reason —
# see that workflow's own comment.
#
# UNRESOLVED, must be validated before this image is ever built for real:
# MetaQuotes' terminal EULA/licensing for redistributing the terminal binary
# inside a container image Nectrix hosts itself — see README.md.

FROM debian:bookworm-slim AS wine-base

# wine32/wine64: MT5/MT4 (and MetaEditor, bundled with each) are
# Windows-only binaries. xvfb + xauth: Wine's own GUI (including its
# installer) needs a display even when run headless — xvfb-run itself
# hard-requires xauth to manage the X authority cookie, and fails
# immediately (exit code 3, before Wine ever starts) without it; this was
# caught for real on the first live CI run of this Dockerfile. x11-apps
# (xwd)/imagemagick (convert): NOT needed by the real image itself — only
# by install-with-diagnostics.sh, to screenshot whatever the installer was
# showing at the moment it failed (a GUI installer's own error dialog is
# otherwise invisible to a text-only Docker build log) — this is how every
# finding in this Dockerfile's history was actually discovered, not
# guessed. winbind/cabextract: real, documented Wine dependencies for a
# clean 64-bit WINEPREFIX. wget/ca-certificates: fetching the real
# MetaQuotes installers below.
RUN dpkg --add-architecture i386 \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
       wine wine32 wine64 xvfb xauth x11-apps imagemagick winbind cabextract wget ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ENV WINEARCH=win64
ENV DISPLAY=:99

RUN mkdir -p /canonical-mt5 /canonical-mt4

# ---- Diagnostic install: never fails the build itself (see the script's own
# header) — this stage's whole point is to always commit a usable layer, so
# /diagnostics stays exportable via `--target diagnostics-export` even when
# the REAL pass/fail gate (install-verified, below) fails.
#
# The real, official, GENERIC MetaQuotes installers — deliberately NOT a
# broker-white-labeled build (those are often locked to that one broker's
# server list); the generic terminal can add any broker's server, matching
# how a real retail user downloading straight from metatrader5.com/
# metatrader4.com would connect regardless of which broker this specific
# account belongs to. Each install also bundles metaeditor64.exe/
# metaeditor.exe, used below to compile our own EA source — no separate
# MetaEditor download needed.
#
# Real, live-verified finding (confirmed against gmag11/MetaTrader5-Docker,
# a maintained real-world Wine+MT5 Docker project): passing a custom
# /portable "/S:<dir>" destination — this Dockerfile's original approach —
# is what was actually broken. /auto ALONE (install-with-diagnostics.sh's
# current approach) is the combination real-world projects actually use;
# each install gets its own WINEPREFIX (separate Wine environments, since
# MT5/MT4 registry/settings shouldn't be shared), and the script discovers
# + copies the real installed files into a canonical directory afterward,
# so nothing downstream has to guess an installer-chosen path.
FROM wine-base AS install-diagnostics

COPY apps/mt-terminal-host/terminal-image/install-with-diagnostics.sh /install-with-diagnostics.sh
RUN chmod +x /install-with-diagnostics.sh

RUN wget -q -O /tmp/mt5setup.exe https://download.mql5.com/cdn/web/metaquotes.software.corp/mt5/mt5setup.exe
RUN /install-with-diagnostics.sh /tmp/mt5setup.exe /wine-mt5 mt5-install terminal64.exe metaeditor64.exe /canonical-mt5
RUN wget -q -O /tmp/mt4setup.exe https://download.mql5.com/cdn/web/metaquotes.software.corp/mt4/mt4setup.exe
RUN /install-with-diagnostics.sh /tmp/mt4setup.exe /wine-mt4 mt4-install terminal.exe metaeditor.exe /canonical-mt4

# ---- Diagnostics export: a minimal, scratch-based stage carrying ONLY
# /diagnostics (screenshots, exit codes, stdout/stderr logs, and the
# discovered terminal/editor paths) — CI's on-failure step exports just
# this stage (`docker buildx build --target diagnostics-export -o
# type=local,...`), never the full multi-hundred-MB wine-base filesystem.
FROM scratch AS diagnostics-export
COPY --from=install-diagnostics /diagnostics /diagnostics

# ---- The real pass/fail gate — everything downstream of this stage only
# builds if both installs genuinely succeeded.
#
# Real, live-verified finding (mt-terminal-install-diagnostics artifacts
# from three separate CI runs): the installer's own process exit code is
# NOT a reliable success/failure signal under Wine — every run exited 1
# even when the wizard visibly completed. What actually matters is whether
# the real files got installed, checked directly here as the authoritative
# signal; the exit code is logged only as non-fatal context.
FROM install-diagnostics AS install-verified

RUN echo "mt5 install exit code (informational only, not gating — see comment above): $(cat /diagnostics/mt5-install.exitcode)"
RUN test -f /canonical-mt5/terminal64.exe || (echo "MT5 install genuinely failed — terminal64.exe is missing; see the mt-terminal-install-diagnostics CI artifact" >&2 && exit 1)
RUN test -f /canonical-mt5/metaeditor64.exe || (echo "MT5 install genuinely failed — metaeditor64.exe is missing; see the mt-terminal-install-diagnostics CI artifact" >&2 && exit 1)
RUN echo "mt4 install exit code (informational only, not gating — see comment above): $(cat /diagnostics/mt4-install.exitcode)"
RUN test -f /canonical-mt4/terminal.exe || (echo "MT4 install genuinely failed — terminal.exe is missing; see the mt-terminal-install-diagnostics CI artifact" >&2 && exit 1)
RUN test -f /canonical-mt4/metaeditor.exe || (echo "MT4 install genuinely failed — metaeditor.exe is missing; see the mt-terminal-install-diagnostics CI artifact" >&2 && exit 1)

# ---- Compile stage: our own EA source, against the verified terminal install ----
# A separate stage (not inlined into the final image) purely so the final
# image never carries the .mq5/.mq4 SOURCE or MetaEditor's own compile logs
# — only the compiled .ex5/.ex4 binaries get copied forward below.
FROM install-verified AS compile-ea

COPY apps/mt5-bridge-gateway/ea/mt5/NectrixBridgeMT5.mq5 /canonical-mt5/MQL5/Experts/NectrixBridgeMT5.mq5
COPY apps/mt5-bridge-gateway/ea/mt4/NectrixBridgeMT4.mq4 /canonical-mt4/MQL4/Experts/NectrixBridgeMT4.mq4

COPY apps/mt-terminal-host/terminal-image/compile-ea.sh /compile-ea.sh
RUN chmod +x /compile-ea.sh

# /compile:<path> /log are MetaEditor's own documented CLI compile switches
# (compile-ea.sh's own header explains why it manually manages Xvfb rather
# than using `xvfb-run -a` — the latter failed near-instantly the one time
# it was tried here). WINEPREFIX must match the prefix each terminal was
# actually installed into (/wine-mt5, /wine-mt4) — real, live-verified
# finding: without it, wine silently falls back to a brand-new default
# prefix that never had the real install's registry/COM setup, and
# MetaEditor fails with OLE/RPC marshalling errors as a result.
RUN /compile-ea.sh /wine-mt5 /canonical-mt5/metaeditor64.exe /canonical-mt5/MQL5/Experts/NectrixBridgeMT5.mq5 /canonical-mt5/MQL5/Experts/NectrixBridgeMT5.ex5
RUN /compile-ea.sh /wine-mt4 /canonical-mt4/metaeditor.exe /canonical-mt4/MQL4/Experts/NectrixBridgeMT4.mq4 /canonical-mt4/MQL4/Experts/NectrixBridgeMT4.ex4

# ---- Final image ----
FROM install-verified AS final

COPY --from=compile-ea /canonical-mt5/MQL5/Experts/NectrixBridgeMT5.ex5 /canonical-mt5/MQL5/Experts/NectrixBridgeMT5.ex5
COPY --from=compile-ea /canonical-mt4/MQL4/Experts/NectrixBridgeMT4.ex4 /canonical-mt4/MQL4/Experts/NectrixBridgeMT4.ex4

COPY apps/mt-terminal-host/terminal-image/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
