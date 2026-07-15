import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { verifyAccessToken, type Session } from "./session";

/**
 * Every broker-accounts Server Component/Action needs both the verified
 * session claims AND the raw token string (to pass through to
 * @nectrix/api-client calls) — proxy.ts already redirected anything without a
 * valid cookie before this ever runs, but this is a second, independent
 * check (mirrors apps/admin-portal's own (portal)/layout.tsx re-verification
 * reasoning) rather than trusting middleware alone.
 */
export async function requireSession(): Promise<{ session: Session; accessToken: string }> {
  const jar = await cookies();
  const token = jar.get("access_token")?.value;
  const session = token ? await verifyAccessToken(token) : null;
  if (!session || !token) {
    redirect("/login");
  }
  return { session, accessToken: token };
}
