import { jwtVerify } from "jose";
import type { AdminPortalRole } from "@nectrix/domain-model";

export interface Session {
  userId: string;
  email: string;
  roles: string[];
}

/**
 * TICKET-012 — the three roles the Admin Portal is reachable by at all (a
 * plain FOLLOWER is rejected at this boundary, not just hidden-by-UI, per
 * docs/12-analytics-notifications-admin.md §12.3's RBAC-within-admin note).
 * MASTER's own scoped views are mostly Phase 1 — this ticket only needs the
 * gate itself to already account for the role.
 */
const PORTAL_ROLES: readonly AdminPortalRole[] = ["ADMIN", "SUPPORT", "MASTER"];

/**
 * Same HS256 secret apps/core-app's JwtService signs with (AuthProperties.Jwt.secret,
 * JWT_SIGNING_SECRET env var — base64, >=256 bits). Decoded via atob rather than
 * Node's Buffer so this works unmodified whether middleware runs on the Edge or
 * Node.js runtime.
 */
function secretKey(): Uint8Array {
  const secret = process.env.JWT_SIGNING_SECRET;
  if (!secret) {
    throw new Error("JWT_SIGNING_SECRET is not set");
  }
  const binary = atob(secret);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

/**
 * Verifies the token's HS256 signature and expiry server-side — genuine
 * enforcement (the secret never reaches client JS), not just hiding nav items.
 * Returns null on any invalid/expired/malformed token rather than throwing,
 * since every caller's response to "not a valid session" is the same redirect.
 */
export async function verifyAccessToken(token: string): Promise<Session | null> {
  try {
    const { payload } = await jwtVerify(token, secretKey(), { algorithms: ["HS256"] });
    const roles = Array.isArray(payload.roles) ? (payload.roles as string[]) : [];
    return {
      userId: String(payload.sub ?? ""),
      email: String(payload.email ?? ""),
      roles,
    };
  } catch {
    return null;
  }
}

export function hasPortalRole(session: Session | null): boolean {
  if (!session) {
    return false;
  }
  return session.roles.some((role) => (PORTAL_ROLES as readonly string[]).includes(role));
}
