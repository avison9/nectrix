# Build context MUST be the repo root:
#   docker build -f apps/admin-portal/Dockerfile -t <tag> .
# (npm workspaces — admin-portal depends on @nectrix/domain-model and
# @nectrix/api-client, resolved via the root package-lock.json — so the whole
# workspace has to be in context, not just this app's own directory.)

# ---- deps stage — cached separately from source so a source-only change
# doesn't reinstall node_modules ----
FROM node:22-alpine AS deps
WORKDIR /workspace
COPY package.json package-lock.json ./
COPY packages/domain-model/package.json packages/domain-model/package.json
COPY packages/api-client/package.json packages/api-client/package.json
COPY apps/admin-portal/package.json apps/admin-portal/package.json
COPY apps/web/package.json apps/web/package.json
RUN npm ci

# ---- build stage ----
FROM node:22-alpine AS build
WORKDIR /workspace
COPY --from=deps /workspace/node_modules ./node_modules
COPY package.json package-lock.json turbo.json eslint.config.mjs ./
COPY packages/domain-model packages/domain-model
COPY packages/api-client packages/api-client
COPY apps/admin-portal apps/admin-portal
# TICKET-012's eslint-plugin-boundaries config resolves apps/web as a defined
# element even though this build doesn't need it — an empty-but-present
# directory keeps that resolution happy without pulling in web's own source.
COPY apps/web/package.json apps/web/package.json
RUN npx turbo run build --filter=admin-portal

# ---- runtime stage ----
FROM node:22-alpine AS runtime
# node:22-alpine already ships a uid/gid 1000 "node" user — matches this
# repo's other Dockerfiles' non-root convention without a gid collision.
#
# The base image's bundled global npm CLI is never invoked at runtime (the
# standalone server is a self-contained `node server.js`) but Trivy still
# flags it — two real HIGH CVEs (CVE-2026-33671 picomatch ReDoS,
# CVE-2026-48815 sigstore verification bypass) in npm's own transitive
# deps. Removed rather than skip-listed, same as every other real Trivy
# finding this repo has fixed.
RUN rm -rf /usr/local/lib/node_modules/npm /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack
WORKDIR /app
# Next.js's "standalone" output (under the APP's own .next/, not the repo
# root) already traces and copies only the node_modules subset the server
# actually needs, recreating the full monorepo-relative path inside itself —
# confirmed by hand: the real entrypoint ends up at apps/admin-portal/server.js
# relative to this copied root, alongside a repo-root node_modules.
COPY --from=build /workspace/apps/admin-portal/.next/standalone ./
COPY --from=build /workspace/apps/admin-portal/.next/static ./apps/admin-portal/.next/static
USER node
EXPOSE 3000
ENV PORT=3000
# Next's standalone server binds to the container's own interface IP rather
# than 0.0.0.0 unless told to — confirmed by hand while smoke-testing this
# exact image locally (curl to localhost failed until this was set).
ENV HOSTNAME=0.0.0.0
ENTRYPOINT ["node", "apps/admin-portal/server.js"]
