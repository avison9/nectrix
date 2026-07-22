import Link from "next/link";
import { getNotificationPreferences } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";
import { logoutAction } from "@/app/login/actions";
import { TradeNotificationsToggle } from "@/components/TradeNotificationsToggle";

// TICKET-115 — mirrors Nectrix.dc.html's `SHARED · PROFILE` section (`vProfile`, `:1631-1662`),
// translated into this app's real Tailwind + CSS-var convention. Only "Trade notifications" is
// really wired here (the mock's own one real preferences element, per this ticket's plan) —
// Appearance has no theme-toggle mechanism anywhere in this app yet (globals.css is
// system-preference-only, no manual override), so that row stays inert, same "rendered but inert"
// precedent app/masters/page.tsx already established for its own not-yet-wired button.
// TICKET-116 — Two-factor authentication is now wired to a real /2fa settings page.
export default async function ProfilePage() {
  const { session, accessToken } = await requireSession();
  const preferences = await getNotificationPreferences(coreAppBaseUrl(), accessToken);
  const tradeNotificationsPref = preferences.find(
    (p) => p.eventType === "copied_trade.opened" && p.channel === "PUSH",
  );
  // No explicit row = catalog default, which is "on" for this event/channel pair (see core-app's
  // NotificationEventTypes).
  const tradeNotificationsEnabled = tradeNotificationsPref?.enabled ?? true;

  return (
    <div className="mx-auto max-w-[760px]">
      <div className="mb-6">
        <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">
          Profile &amp; settings
        </h1>
        <p className="mt-1.5 text-sm text-[var(--text-2)]">
          Manage your account, security and preferences.
        </p>
      </div>

      <div className="mb-4 flex flex-wrap items-center gap-4 rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5.5">
        <div className="flex h-[60px] w-[60px] flex-none items-center justify-center rounded-full bg-[var(--accent)] text-xl font-semibold text-white">
          {session.email.charAt(0).toUpperCase()}
        </div>
        <div className="min-w-0 flex-1">
          <div className="truncate text-[15px] font-medium text-[var(--text)]">{session.email}</div>
        </div>
      </div>

      <div className="mb-4 rounded-2xl border border-[var(--border)] bg-[var(--surface)] px-5.5">
        <div className="pb-2 pt-4 text-xs font-semibold uppercase tracking-wide text-[var(--text-3)]">
          Preferences
        </div>

        <div className="flex items-center justify-between border-t border-[var(--border)] py-3.5">
          <div>
            <div className="text-sm font-medium text-[var(--text)]">Appearance</div>
            <div className="mt-0.5 text-xs text-[var(--text-3)]">Follows your system theme</div>
          </div>
          <button
            type="button"
            disabled
            title="Manual theme override isn't available yet — follows your system's light/dark setting"
            className="h-9 cursor-not-allowed rounded-[10px] border border-[var(--border)] px-4 text-[13px] font-semibold text-[var(--text-3)]"
          >
            Auto
          </button>
        </div>

        <div className="flex items-center justify-between border-t border-[var(--border)] py-3.5">
          <div>
            <div className="text-sm font-medium text-[var(--text)]">Trade notifications</div>
            <div className="mt-0.5 text-xs text-[var(--text-3)]">
              Push alerts when a copied trade opens
            </div>
          </div>
          <TradeNotificationsToggle initialEnabled={tradeNotificationsEnabled} />
        </div>

        <div className="flex items-center justify-between border-t border-[var(--border)] py-3.5">
          <div>
            <div className="text-sm font-medium text-[var(--text)]">Two-factor authentication</div>
            <span
              className={`mt-1 inline-block rounded-full px-2.5 py-1 text-[12px] font-semibold ${
                session.twoFactorEnabled
                  ? "bg-[var(--pos)]/15 text-[var(--pos)]"
                  : "bg-[var(--surface-2)] text-[var(--text-3)]"
              }`}
            >
              {session.twoFactorEnabled ? "Enabled" : "Not enabled"}
            </span>
          </div>
          <Link
            href="/2fa"
            className="flex h-9 items-center rounded-[10px] border border-[var(--border)] px-4 text-[13px] font-semibold text-[var(--accent)] transition-colors hover:bg-[var(--surface-2)]"
          >
            {session.twoFactorEnabled ? "Manage" : "Enable"}
          </Link>
        </div>

        {!session.roles.includes("MASTER") && !session.roles.includes("FOLLOWER") && (
          <div className="flex items-center justify-between border-t border-[var(--border)] py-3.5">
            <div>
              <div className="text-sm font-medium text-[var(--text)]">Account tier</div>
              <div className="mt-0.5 text-xs text-[var(--text-3)]">
                Request to become a Master or Follower
              </div>
            </div>
            <Link
              href="/account/tier-change"
              className="flex h-9 items-center rounded-[10px] border border-[var(--border)] px-4 text-[13px] font-semibold text-[var(--accent)] transition-colors hover:bg-[var(--surface-2)]"
            >
              Request
            </Link>
          </div>
        )}
      </div>

      <form action={logoutAction}>
        <button
          type="submit"
          className="flex h-11 items-center gap-2 rounded-[11px] border border-[var(--border)] px-5.5 text-[13.5px] font-semibold text-[var(--neg)] hover:bg-[var(--neg)]/8"
        >
          Sign out
        </button>
      </form>
    </div>
  );
}
