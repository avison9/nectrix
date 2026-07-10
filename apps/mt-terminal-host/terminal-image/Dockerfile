# Build context MUST be the repo root:
#   docker build -f apps/mt-terminal-host/terminal-image/Dockerfile -t mt-terminal-image .
#
# Nectrix-hosted MT5/MT4 terminals: Wine + Xvfb + a real MetaTrader terminal
# + our own EA, compiled INSIDE this same build (a real MT5/MT4 terminal
# install always bundles MetaEditor, so there's no need for a separate
# compile step/image elsewhere — one build, one source of truth). See
# entrypoint.sh for the runtime config-generation + launch sequence, and
# apps/mt-terminal-host/README.md's "Honest limitation" section for exactly
# what has/hasn't been verified. Wired into CI as a workflow_dispatch-only
# job (build-terminal-image.yml), not on every push, for exactly that
# reason — see that workflow's own comment.
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
# caught for real on the first live CI run of this Dockerfile (see
# apps/mt-terminal-host/README.md's "Live verification" section). x11-apps
# (xwd)/imagemagick (convert): NOT needed by the real image itself — only
# by install-with-diagnostics.sh, to screenshot whatever the installer was
# showing at the moment it failed (a GUI installer's own error dialog is
# otherwise invisible to a text-only Docker build log) — this is how a
# second real CI run found that /auto does NOT suppress the installer's
# own final "Finish" confirmation screen (both MT5 and MT4 genuinely
# complete their install and then just sit there waiting for a click).
# xdotool: dismisses that screen (see install-with-diagnostics.sh).
# winbind/cabextract: real, documented Wine dependencies for a clean
# 64-bit WINEPREFIX. wget/ca-certificates: fetching the real MetaQuotes
# installers below.
RUN dpkg --add-architecture i386 \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
       wine wine32 wine64 xvfb xauth x11-apps imagemagick xdotool winbind cabextract wget ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ENV WINEPREFIX=/wine
ENV WINEARCH=win64
ENV DISPLAY=:99

RUN mkdir -p /wine /terminal-mt5 /terminal-mt4

# ---- Diagnostic install: never fails the build itself (see the script's own
# header) — this stage's whole point is to always commit a usable layer, so
# /diagnostics stays exportable via `--target diagnostics-export` even when
# the REAL pass/fail gate (install-verified, below) fails. The real,
# official, GENERIC MetaQuotes installers — deliberately NOT a
# broker-white-labeled build (those are often locked to that one broker's
# server list); the generic terminal can add any broker's server, matching
# how a real retail user downloading straight from metatrader5.com/
# metatrader4.com would connect regardless of which broker this specific
# account belongs to. /auto /portable /S:"<dir>" are MetaQuotes' own
# documented silent-install switches. Each install also bundles
# metaeditor64.exe/metaeditor.exe, used below to compile our own EA source
# — no separate MetaEditor download needed.
FROM wine-base AS install-diagnostics

COPY apps/mt-terminal-host/terminal-image/install-with-diagnostics.sh /install-with-diagnostics.sh
RUN chmod +x /install-with-diagnostics.sh

RUN wget -q -O /tmp/mt5setup.exe https://download.mql5.com/cdn/web/metaquotes.software.corp/mt5/mt5setup.exe
RUN /install-with-diagnostics.sh /tmp/mt5setup.exe /terminal-mt5 mt5-install
RUN wget -q -O /tmp/mt4setup.exe https://download.mql5.com/cdn/web/metaquotes.software.corp/mt4/mt4setup.exe
RUN /install-with-diagnostics.sh /tmp/mt4setup.exe /terminal-mt4 mt4-install

# ---- Diagnostics export: a minimal, scratch-based stage carrying ONLY
# /diagnostics (screenshots, exit codes, stdout/stderr logs) — CI's
# on-failure step exports just this stage
# (`docker buildx build --target diagnostics-export -o type=local,...`),
# never the full multi-hundred-MB wine-base filesystem.
FROM scratch AS diagnostics-export
COPY --from=install-diagnostics /diagnostics /diagnostics

# ---- The real pass/fail gate — everything downstream of this stage only
# builds if both installs genuinely succeeded.
FROM install-diagnostics AS install-verified

RUN test "$(cat /diagnostics/mt5-install.exitcode)" = "0" || (echo "MT5 install failed — see the mt-terminal-install-diagnostics CI artifact" >&2 && exit 1)
RUN test -f /terminal-mt5/metaeditor64.exe || (echo "MT5 install reported success but metaeditor64.exe is missing" >&2 && exit 1)
RUN test "$(cat /diagnostics/mt4-install.exitcode)" = "0" || (echo "MT4 install failed — see the mt-terminal-install-diagnostics CI artifact" >&2 && exit 1)
RUN test -f /terminal-mt4/metaeditor.exe || (echo "MT4 install reported success but metaeditor.exe is missing" >&2 && exit 1)

# ---- Compile stage: our own EA source, against the verified terminal install ----
# A separate stage (not inlined into the final image) purely so the final
# image never carries the .mq5/.mq4 SOURCE or MetaEditor's own compile logs
# — only the compiled .ex5/.ex4 binaries get copied forward below.
FROM install-verified AS compile-ea

COPY apps/mt5-bridge-gateway/ea/mt5/NectrixBridgeMT5.mq5 /terminal-mt5/MQL5/Experts/NectrixBridgeMT5.mq5
COPY apps/mt5-bridge-gateway/ea/mt4/NectrixBridgeMT4.mq4 /terminal-mt4/MQL4/Experts/NectrixBridgeMT4.mq4

# /compile:<path> /log are MetaEditor's own documented CLI compile switches
# (exit code and /log's contents are how a real build would detect a
# compile error — UNVERIFIED here, this source has never been run through
# a real MetaEditor; a genuine compile error would only surface the first
# time this stage actually runs).
RUN xvfb-run -a wine /terminal-mt5/metaeditor64.exe "/compile:/terminal-mt5/MQL5/Experts/NectrixBridgeMT5.mq5" /log \
    && test -f /terminal-mt5/MQL5/Experts/NectrixBridgeMT5.ex5
RUN xvfb-run -a wine /terminal-mt4/metaeditor.exe "/compile:/terminal-mt4/MQL4/Experts/NectrixBridgeMT4.mq4" /log \
    && test -f /terminal-mt4/MQL4/Experts/NectrixBridgeMT4.ex4

# ---- Final image ----
FROM install-verified AS final

COPY --from=compile-ea /terminal-mt5/MQL5/Experts/NectrixBridgeMT5.ex5 /terminal-mt5/MQL5/Experts/NectrixBridgeMT5.ex5
COPY --from=compile-ea /terminal-mt4/MQL4/Experts/NectrixBridgeMT4.ex4 /terminal-mt4/MQL4/Experts/NectrixBridgeMT4.ex4

COPY apps/mt-terminal-host/terminal-image/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
