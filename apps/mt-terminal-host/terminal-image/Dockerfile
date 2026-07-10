# Build context MUST be the repo root:
#   docker build -f apps/mt-terminal-host/terminal-image/Dockerfile -t mt-terminal-image .
#
# Nectrix-hosted MT5/MT4 terminals: Wine + Xvfb + a real MetaTrader terminal
# + our own EA, compiled INSIDE this same build (a real MT5/MT4 terminal
# install always bundles MetaEditor, so there's no need for a separate
# compile step/image elsewhere — one build, one source of truth). See
# entrypoint.sh for the runtime config-generation + launch sequence, and
# apps/mt-terminal-host/README.md's "Honest limitation" section for exactly
# what has/hasn't been verified — this Dockerfile has never been built
# anywhere with a real Wine/Docker-in-Docker path to it, only designed
# against real, documented MetaQuotes installer/MetaEditor/config behavior.
# Wired into CI as a workflow_dispatch-only job (main-pipeline.yml), not on
# every push, for exactly that reason — see that job's own comment.
#
# UNRESOLVED, must be validated before this image is ever built for real:
# MetaQuotes' terminal EULA/licensing for redistributing the terminal binary
# inside a container image Nectrix hosts itself — see README.md.

FROM debian:bookworm-slim AS wine-base

# wine32/wine64: MT5/MT4 (and MetaEditor, bundled with each) are
# Windows-only binaries. xvfb: Wine's own GUI (including its installer)
# needs a display even when run headless. winbind/cabextract: real,
# documented Wine dependencies for a clean 64-bit WINEPREFIX. wget/
# ca-certificates: fetching the real MetaQuotes installers below.
# UNVERIFIED in this devcontainer (no Wine here) — this exact package
# set/install sequence follows Wine's own documented Debian install guide,
# not independently confirmed against a real build.
RUN dpkg --add-architecture i386 \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
       wine wine32 wine64 xvfb winbind cabextract wget ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ENV WINEPREFIX=/wine
ENV WINEARCH=win64
ENV DISPLAY=:99

RUN mkdir -p /wine /terminal-mt5 /terminal-mt4

# The real, official, GENERIC MetaQuotes installers — deliberately NOT a
# broker-white-labeled build (those are often locked to that one broker's
# server list); the generic terminal can add any broker's server, matching
# how a real retail user downloading straight from metatrader5.com/
# metatrader4.com would connect regardless of which broker this specific
# account belongs to. /auto /portable /S:"<dir>" are MetaQuotes' own
# documented silent-install switches — UNVERIFIED here (no Wine to run
# them against), flagged the same as the package list above. Each install
# also bundles metaeditor64.exe/metaeditor.exe, used below to compile our
# own EA source — no separate MetaEditor download needed.
RUN wget -q -O /tmp/mt5setup.exe https://download.mql5.com/cdn/web/metaquotes.software.corp/mt5/mt5setup.exe \
    && xvfb-run -a wine /tmp/mt5setup.exe /auto /portable "/S:/terminal-mt5" \
    && rm -f /tmp/mt5setup.exe
RUN wget -q -O /tmp/mt4setup.exe https://download.mql5.com/cdn/web/metaquotes.software.corp/mt4/mt4setup.exe \
    && xvfb-run -a wine /tmp/mt4setup.exe /auto /portable "/S:/terminal-mt4" \
    && rm -f /tmp/mt4setup.exe

# ---- Compile stage: our own EA source, against the terminal install above ----
# A separate stage (not inlined into the final image) purely so the final
# image never carries the .mq5/.mq4 SOURCE or MetaEditor's own compile logs
# — only the compiled .ex5/.ex4 binaries get copied forward below.
FROM wine-base AS compile-ea

COPY apps/mt5-bridge-gateway/ea/mt5/NectrixBridgeMT5.mq5 /terminal-mt5/MQL5/Experts/NectrixBridgeMT5.mq5
COPY apps/mt5-bridge-gateway/ea/mt4/NectrixBridgeMT4.mq4 /terminal-mt4/MQL4/Experts/NectrixBridgeMT4.mq4

# /compile:<path> /log are MetaEditor's own documented CLI compile switches
# (exit code and /log's contents are how a real build would detect a
# compile error — UNVERIFIED here, this source has never been run through
# a real MetaEditor; a genuine compile error would only surface the first
# time this stage actually runs in an environment with real Wine/MetaEditor).
RUN xvfb-run -a wine /terminal-mt5/metaeditor64.exe "/compile:/terminal-mt5/MQL5/Experts/NectrixBridgeMT5.mq5" /log \
    && test -f /terminal-mt5/MQL5/Experts/NectrixBridgeMT5.ex5
RUN xvfb-run -a wine /terminal-mt4/metaeditor.exe "/compile:/terminal-mt4/MQL4/Experts/NectrixBridgeMT4.mq4" /log \
    && test -f /terminal-mt4/MQL4/Experts/NectrixBridgeMT4.ex4

# ---- Final image ----
FROM wine-base AS final

COPY --from=compile-ea /terminal-mt5/MQL5/Experts/NectrixBridgeMT5.ex5 /terminal-mt5/MQL5/Experts/NectrixBridgeMT5.ex5
COPY --from=compile-ea /terminal-mt4/MQL4/Experts/NectrixBridgeMT4.ex4 /terminal-mt4/MQL4/Experts/NectrixBridgeMT4.ex4

COPY apps/mt-terminal-host/terminal-image/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
