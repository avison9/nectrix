"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { logoutAction } from "@/app/login/actions";
import { initialsFor } from "@/lib/initials";

interface NavItem {
  href: string;
  label: string;
  icon: string; // an SVG <path> `d` attribute, matching Nectrix.dc.html's icon convention
}

// Nectrix.dc.html's own `ICON` object (:1756-1773) — reused verbatim so every nav glyph matches
// the mock exactly, not an approximation.
const ICON = {
  dashboard: "M3 3h7v7H3zM14 3h7v7h-7zM14 14h7v7h-7zM3 14h7v7H3z",
  analytics: "M4 20V10M10 20V4M16 20v-7M22 20H2",
  followers:
    "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8M22 21v-2a4 4 0 0 0-3-3.87M18 3.13a4 4 0 0 1 0 7.75",
  ib: "M10 13a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-1 1M14 11a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l1-1",
  nectrix:
    "M18 8a3 3 0 1 0 0-6 3 3 0 0 0 0 6M6 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6M18 22a3 3 0 1 0 0-6 3 3 0 0 0 0 6M8.6 13.5l6.8 4M15.4 6.5l-6.8 4",
  commission: "M12 1v22M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6",
  accounts: "M3 5h18v14H3zM3 10h18M7 15h4",
  admin: "M12 2l8 4v6c0 5-3.5 8-8 10-4.5-2-8-5-8-10V6zM9 12l2 2 4-4",
  profile: "M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8",
  signout: "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9",
  inbox: "M4 5h16v14H4zM4 7l8 6 8-6",
  terms: "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6M9 13h6M9 17h6",
  connect: "M10 13a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-1 1M14 11a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l1-1",
  onboarding: "M22 11.08V12a10 10 0 1 1-5.93-9.14M22 4L12 14.01l-3-3",
  copy: "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z",
  // Not in the mock's ICON set (Trade History / Discover Masters aren't in MASTER_NAV/FOLLOWER_NAV
  // at all) — these two are real, already-shipped apps/web pages kept in nav rather than stranded;
  // icons carried over from this file's own pre-existing choices.
  tradeHistory: "M3 3v18h18M7 14l3-3 3 3 5-5",
  discover: "M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16ZM21 21l-4.35-4.35",
};

// Mirrors Nectrix.dc.html's `MASTER_NAV` (:1775-1786) in the same order. `followers`/`ib`/`nectrix`/
// `inbox`/`commission` route to placeholder pages (TICKET-118/119/120/207 — none built yet, see each
// page's own comment); `trade-history`/`master-profile`/`masters` are real, already-shipped pages
// that aren't in the mock's nav array at all, kept here rather than stranding already-built features.
function masterNavItems(): NavItem[] {
  return [
    { href: "/dashboard", label: "Dashboard", icon: ICON.dashboard },
    { href: "/analytics", label: "Analytics", icon: ICON.analytics },
    { href: "/master/followers", label: "Followers", icon: ICON.followers },
    { href: "/ib-partners", label: "IB Partners", icon: ICON.ib },
    { href: "/referrals", label: "Nectrix Referrals", icon: ICON.nectrix },
    { href: "/inbox", label: "Inbox", icon: ICON.inbox },
    { href: "/commission", label: "Commission", icon: ICON.commission },
    { href: "/trade-history", label: "Trade History", icon: ICON.tradeHistory },
    { href: "/terms", label: "Terms & Conditions", icon: ICON.terms },
    { href: "/broker-accounts", label: "Accounts", icon: ICON.accounts },
    { href: "/master-profile", label: "Master Profile", icon: ICON.profile },
    { href: "/masters", label: "Discover Masters", icon: ICON.discover },
  ];
}

// Mirrors Nectrix.dc.html's `FOLLOWER_NAV` (:1787-1795) in the same order, plus the same two
// already-shipped extras (`trade-history`, `masters`) and `broker-accounts` (a Follower's own linked
// account, same page Master's "Accounts" uses).
function followerNavItems(): NavItem[] {
  return [
    { href: "/dashboard", label: "Dashboard", icon: ICON.dashboard },
    { href: "/copy-relationships", label: "My Masters", icon: ICON.connect },
    { href: "/onboarding", label: "Onboarding", icon: ICON.onboarding },
    { href: "/follower/analytics", label: "Analytics", icon: ICON.analytics },
    { href: "/copy-settings", label: "Copy Settings", icon: ICON.copy },
    { href: "/follower/referrals", label: "Referrals", icon: ICON.nectrix },
    { href: "/follower/commission", label: "Commission", icon: ICON.commission },
    { href: "/trade-history", label: "Trade History", icon: ICON.tradeHistory },
    { href: "/broker-accounts", label: "Broker Accounts", icon: ICON.accounts },
    { href: "/masters", label: "Discover Masters", icon: ICON.discover },
  ];
}

/**
 * The mock's "Admin" item is only ever in the `MASTER_NAV` array, but its own content is
 * "Super-admin only" (`vMasterAdmin`, `:1050-1051`) — real invite-master/suspend-account actions
 * already exist for real in apps/admin-portal's Users page, this is a placeholder mirror (see
 * app/(app)/admin/page.tsx). Gated on SUPER_ADMIN specifically and appended regardless of which
 * nav list rendered, so a SUPER_ADMIN account holding neither MASTER nor FOLLOWER (falls into the
 * Follower branch by dashboard/page.tsx's own role-gate precedent) still reaches it.
 */
function navItems(roles: string[]): NavItem[] {
  const isMaster = roles.includes("MASTER");
  const items = isMaster ? masterNavItems() : followerNavItems();
  if (roles.includes("SUPER_ADMIN")) {
    items.push({ href: "/admin", label: "Admin", icon: ICON.admin });
  }
  return items;
}

function NavIcon({ d }: { d: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      width={18}
      height={18}
      fill="none"
      stroke="currentColor"
      strokeWidth={1.7}
      strokeLinecap="round"
      strokeLinejoin="round"
      className="shrink-0"
    >
      <path d={d} />
    </svg>
  );
}

export function Sidebar({ roles, email }: { roles: string[]; email: string }) {
  const pathname = usePathname();
  const items = navItems(roles);

  return (
    <aside className="flex w-[250px] shrink-0 flex-col border-r border-[var(--border)] bg-[var(--surface)]">
      <div className="flex items-center gap-2.5 px-[18px] py-5 pb-3.5">
        <span className="text-[16px] font-semibold tracking-tight text-[var(--text)]">Nectrix</span>
      </div>

      <nav className="flex flex-1 flex-col gap-0.5 overflow-y-auto px-3 pb-3">
        {items.map((item) => {
          const active = pathname === item.href || pathname.startsWith(`${item.href}/`);
          return (
            <Link
              key={item.href}
              href={item.href}
              className={`flex items-center gap-2.5 rounded-[10px] px-2.5 py-2 text-[13.5px] font-medium transition-colors ${
                active
                  ? "bg-[var(--accent-2)] text-[var(--accent)]"
                  : "text-[var(--text-2)] hover:bg-[var(--surface-2)]"
              }`}
            >
              <NavIcon d={item.icon} />
              <span className="flex-1">{item.label}</span>
            </Link>
          );
        })}
      </nav>

      {/* Mirrors Nectrix.dc.html's `footNav` + user block (:2010-2013, sidebar bottom section) —
          moved here from Topbar so the shell's bottom structure matches the mock. */}
      <div className="flex flex-col gap-0.5 border-t border-[var(--border)] px-3 py-2.5">
        <Link
          href="/profile"
          className={`flex items-center gap-2.5 rounded-[10px] px-2.5 py-2 text-[13.5px] font-medium transition-colors ${
            pathname === "/profile"
              ? "bg-[var(--accent-2)] text-[var(--accent)]"
              : "text-[var(--text-2)] hover:bg-[var(--surface-2)]"
          }`}
        >
          <NavIcon d={ICON.profile} />
          <span>Profile &amp; settings</span>
        </Link>
        <form action={logoutAction}>
          <button
            type="submit"
            className="flex w-full items-center gap-2.5 rounded-[10px] px-2.5 py-2 text-left text-[13.5px] font-medium text-[var(--text-2)] transition-colors hover:bg-[var(--surface-2)]"
          >
            <NavIcon d={ICON.signout} />
            <span>Sign out</span>
          </button>
        </form>

        <div className="flex items-center gap-2.5 px-2.5 pb-1 pt-2">
          <div className="flex h-[30px] w-[30px] shrink-0 items-center justify-center rounded-full border border-[var(--border)] bg-[var(--surface-2)] text-xs font-semibold text-[var(--text-2)]">
            {initialsFor(email)}
          </div>
          <div className="min-w-0 flex-1 leading-tight">
            <div className="truncate text-[13px] font-medium text-[var(--text)]">{email}</div>
            <div className="truncate text-[11px] text-[var(--text-3)]">{roles.join(", ") || "USER"}</div>
          </div>
        </div>
      </div>
    </aside>
  );
}
