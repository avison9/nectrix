import { NextResponse, type NextRequest } from "next/server";
import { hasPortalRole, verifyAccessToken } from "@/lib/session";

const PUBLIC_PATHS = ["/login"];

/**
 * TICKET-012 AC1/AC2 — the Admin Portal's real gate: a missing/invalid/expired
 * access_token cookie, or one without ADMIN/SUPPORT/MASTER among its `roles`
 * claim, is redirected to /login rather than ever rendering a protected page.
 * This is genuine server-side verification of the token's signature (see
 * lib/session.ts), not a client-side check that a modified/replayed cookie
 * could bypass.
 */
export async function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;
  if (PUBLIC_PATHS.some((path) => pathname === path || pathname.startsWith(`${path}/`))) {
    return NextResponse.next();
  }

  const token = request.cookies.get("access_token")?.value;
  const session = token ? await verifyAccessToken(token) : null;

  if (!hasPortalRole(session)) {
    const loginUrl = new URL("/login", request.url);
    return NextResponse.redirect(loginUrl);
  }

  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
