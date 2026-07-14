import { jwtVerify } from "jose";

export interface Session {
  userId: string;
  email: string;
  roles: string[];
  twoFactorEnabled: boolean;
}

/**
 * Same HS256 secret apps/core-app's JwtService signs with (AuthProperties.Jwt.secret,
 * JWT_SIGNING_SECRET env var — base64, >=256 bits). Decoded via atob rather than
 * Node's Buffer so this works unmodified whether middleware runs on the Edge or
 * Node.js runtime. Mirrors apps/admin-portal's own lib/session.ts exactly.
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
 *
 * <p>Unlike apps/admin-portal's session, this app has no role gate — TICKET-110's
 * broker-linking flow (and this app generally) is reachable by any authenticated
 * user (FOLLOWER by default), not just ADMIN/SUPPORT/MASTER.
 */
export async function verifyAccessToken(token: string): Promise<Session | null> {
  try {
    const { payload } = await jwtVerify(token, secretKey(), { algorithms: ["HS256"] });
    const roles = Array.isArray(payload.roles) ? (payload.roles as string[]) : [];
    return {
      userId: String(payload.sub ?? ""),
      email: String(payload.email ?? ""),
      roles,
      twoFactorEnabled: Boolean(payload.two_factor_enabled),
    };
  } catch {
    return null;
  }
}
