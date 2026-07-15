// Server-side only — every call site is a Server Component/Action/route
// handler. CORE_APP_BASE_URL is deliberately not NEXT_PUBLIC_* (see
// docs/13-technology-stack.md §13.3's "auth domain... independently
// controllable" note): the browser never talks to core-app directly, only to
// this app's own server, which proxies with the bearer token attached.
// Mirrors apps/admin-portal's own lib/core-app.ts exactly.
export function coreAppBaseUrl(): string {
  const url = process.env.CORE_APP_BASE_URL;
  if (!url) {
    throw new Error("CORE_APP_BASE_URL is not set");
  }
  return url;
}
