import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { verifyAccessToken } from "@/lib/session";
import { LandingPage } from "@/components/LandingPage";

/**
 * TICKET-114 — proxy.ts now allows "/" through unauthenticated (see its own PUBLIC_PATHS comment)
 * so the public landing page can render here; this is what does the equivalent auth check itself,
 * same verifyAccessToken call proxy.ts uses, so an authenticated visitor still lands on
 * /broker-accounts exactly as before — nothing about that path changes.
 */
export default async function Home() {
  const jar = await cookies();
  const token = jar.get("access_token")?.value;
  const session = token ? await verifyAccessToken(token) : null;

  if (session) {
    redirect("/broker-accounts");
  }

  return <LandingPage />;
}
