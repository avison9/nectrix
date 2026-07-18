import { listNotifications } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";
import { Sidebar } from "@/components/Sidebar";
import { Topbar } from "@/components/Topbar";

/**
 * TICKET-116 — the shared shell every authenticated page now renders inside, closing the
 * "apps/web has zero chrome" gap (every page previously hand-rolled its own `<main>`) — mirrors
 * apps/admin-portal's own `(portal)/layout.tsx` structure, minus the role gate: unlike that portal,
 * this app has no role restriction at the shell level (any authenticated user reaches it, same
 * reasoning `lib/session.ts`'s own Javadoc already documents for `verifyAccessToken`).
 */
export default async function AppLayout({ children }: { children: React.ReactNode }) {
  const { session, accessToken } = await requireSession();
  const unread = await listNotifications(coreAppBaseUrl(), accessToken, true);

  return (
    <div className="flex min-h-screen">
      <Sidebar roles={session.roles} />
      <div className="flex min-w-0 flex-1 flex-col">
        <Topbar email={session.email} roles={session.roles} unreadCount={unread.length} />
        <main className="flex-1 overflow-y-auto p-6">{children}</main>
      </div>
    </div>
  );
}
