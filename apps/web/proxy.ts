import { NextResponse, type NextRequest } from "next/server";
import { verifyAccessToken } from "@/lib/session";

// TICKET-118 — "/accept-invite" is the invitation-acceptance entry point: a Follower clicks the
// emailed link before they have any session at all, so it must be public. It calls the real
// public GET /invitations/by-token/{token} and POST /auth/accept-invite endpoints itself.
// TICKET-112 — /masters (leaderboard) and /masters/{id} (public profile) are the platform's
// public discovery/marketing surface (docs/10-portfolio-social-trading.md §10.2): reachable by
// anyone, logged in or not.
// TICKET-114 — "/" is the public landing page (app/page.tsx does its own auth check and
// redirects an authenticated visitor to /broker-accounts itself, so this doesn't weaken that
// path); "/register" is the self-serve Individual registration form, also public by design (the
// one deliberate exception to "no self-registration anywhere" — see core-app's
// UserProvisioningApi).
// TICKET-116 — "/request-invite" is PUBLIC · REQUEST INVITE, sitting between TICKET-112
// (discovery, real) and TICKET-118 (invitation acceptance, not built) — the form itself is
// reachable by anyone, its submit action is inert until that ticket lands (see the page's own
// Javadoc-equivalent comment).
const PUBLIC_PATHS = ["/", "/login", "/masters", "/register", "/request-invite", "/accept-invite"];

/**
 * TICKET-110 AC6 — this app's real gate: a missing/invalid/expired access_token
 * cookie is redirected to /login rather than ever rendering a protected page
 * (there is no standalone public "connect your broker" entry point, per
 * docs/07-auth-onboarding-broker-linking.md §7.5). Genuine server-side
 * verification of the token's signature (see lib/session.ts), not a
 * client-side check a modified/replayed cookie could bypass. Unlike
 * apps/admin-portal, any authenticated user passes here — there is no role
 * gate on this app.
 */
export async function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;
  if (PUBLIC_PATHS.some((path) => pathname === path || pathname.startsWith(`${path}/`))) {
    return NextResponse.next();
  }

  const token = request.cookies.get("access_token")?.value;
  const session = token ? await verifyAccessToken(token) : null;

  if (!session) {
    const loginUrl = new URL("/login", request.url);
    return NextResponse.redirect(loginUrl);
  }

  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
