import { listNotifications } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import type { Session } from "@/lib/session";
import { Sidebar } from "./Sidebar";
import { Topbar } from "./Topbar";

/**
 * The Sidebar/Topbar shell, factored out of `(app)/layout.tsx` so pages that live *outside* the
 * `(app)` route group — `/masters` and `/masters/[id]`, the public discovery directory, reachable
 * by anonymous visitors too — can still render it for an already-logged-in visitor. Those pages
 * can't move into `(app)` itself: that group's own layout calls `requireSession()`, which redirects
 * anonymous visitors away entirely, breaking the public-browsing case TICKET-112 requires. Instead
 * those pages do their own soft (non-redirecting) session check and wrap their content in this
 * exact same shell only when one exists — otherwise a logged-in user navigating to "Discover
 * Masters" from the Sidebar's own nav item lost the entire shell with no way back except the
 * browser's back button.
 */
export async function AppShell({
  session,
  accessToken,
  children,
}: {
  session: Session;
  accessToken: string;
  children: React.ReactNode;
}) {
  const unread = await listNotifications(coreAppBaseUrl(), accessToken, true);

  return (
    <div className="flex min-h-screen">
      <Sidebar roles={session.roles} email={session.email} />
      <div className="flex min-w-0 flex-1 flex-col">
        <Topbar unreadCount={unread.length} />
        <main className="flex-1 overflow-y-auto p-6">{children}</main>
      </div>
    </div>
  );
}
