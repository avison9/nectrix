"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

interface NavItem {
  href: string;
  label: string;
  icon: string; // an SVG <path> `d` attribute, matching Nectrix.dc.html's icon convention
}

// TICKET-116 — mirrors apps/admin-portal's own Sidebar.tsx structure exactly, translated to this
// app's role model: MASTER-only items (Analytics->master view, Master Profile, Terms) only render
// for a caller holding that role; everything else is reachable regardless of mode (Individual,
// invited Follower, or admin-provisioned Master alike).
function navItems(roles: string[]): NavItem[] {
  const isMaster = roles.includes("MASTER");
  const items: NavItem[] = [
    { href: "/dashboard", label: "Dashboard", icon: "M3 3h7v9H3zM14 3h7v5h-7zM14 12h7v9h-7zM3 16h7v5H3z" },
    {
      href: isMaster ? "/analytics" : "/follower/analytics",
      label: "Analytics",
      icon: "M3 3v18h18M18.7 8l-5.1 5.1-2.8-2.8L7 14",
    },
    { href: "/trade-history", label: "Trade History", icon: "M3 3v18h18M7 14l3-3 3 3 5-5" },
    { href: "/copy-relationships", label: "My Masters", icon: "M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM23 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75" },
    { href: "/broker-accounts", label: "Broker Accounts", icon: "M21 12V7H5a2 2 0 0 1 0-4h14v4M3 5v14a2 2 0 0 0 2 2h16v-5M18 12a2 2 0 0 0 0 4h4v-4Z" },
    { href: "/masters", label: "Discover Masters", icon: "M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16ZM21 21l-4.35-4.35" },
  ];
  if (isMaster) {
    items.push(
      { href: "/master-profile", label: "Master Profile", icon: "M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8Z" },
      { href: "/terms", label: "Terms & Conditions", icon: "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8ZM14 2v6h6M16 13H8M16 17H8M10 9H8" },
    );
  }
  return items;
}

export function Sidebar({ roles }: { roles: string[] }) {
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
                <path d={item.icon} />
              </svg>
              <span className="flex-1">{item.label}</span>
            </Link>
          );
        })}
      </nav>
    </aside>
  );
}
