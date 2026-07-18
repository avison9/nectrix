import { requireSession } from "@/lib/auth";
import { AppShell } from "@/components/AppShell";

/**
 * TICKET-116 — the shared shell every authenticated page now renders inside, closing the
 * "apps/web has zero chrome" gap (every page previously hand-rolled its own `<main>`) — mirrors
 * apps/admin-portal's own `(portal)/layout.tsx` structure, minus the role gate: unlike that portal,
 * this app has no role restriction at the shell level (any authenticated user reaches it, same
 * reasoning `lib/session.ts`'s own Javadoc already documents for `verifyAccessToken`).
 */
export default async function AppLayout({ children }: { children: React.ReactNode }) {
  const { session, accessToken } = await requireSession();
  return (
    <AppShell session={session} accessToken={accessToken}>
      {children}
    </AppShell>
  );
}
