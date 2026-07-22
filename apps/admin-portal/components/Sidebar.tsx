"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

interface NavItem {
  href: string;
  label: string;
  icon: string; // an SVG <path> `d` attribute, matching Nectrix.dc.html's icon convention
}

// TICKET-012's own scope list: System Health / Users / Disputes / Audit Log
// (each a stub except Users' provisioning form and Audit Log, which are real).
// TICKET-122 added Tier-Change Requests alongside them.
const NAV_ITEMS: NavItem[] = [
  {
    href: "/system-health",
    label: "System Health",
    icon: "M22 12h-4l-3 9L9 3l-3 9H2",
  },
  {
    href: "/users",
    label: "Users",
    icon: "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M11 3a4 4 0 1 1 0 8 4 4 0 0 1 0-8ZM22 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75",
  },
  {
    href: "/tier-change-requests",
    label: "Tier-Change Requests",
    icon: "M17 1l4 4-4 4M3 11V9a4 4 0 0 1 4-4h14M7 23l-4-4 4-4M21 13v2a4 4 0 0 1-4 4H3",
  },
  {
    href: "/disputes",
    label: "Disputes",
    icon: "M12 8v4M12 16h.01M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0Z",
  },
  {
    href: "/audit-log",
    label: "Audit Log",
    icon: "M4 19.5A2.5 2.5 0 0 1 6.5 17H20M4 4.5A2.5 2.5 0 0 1 6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15Z",
  },
];

export function Sidebar() {
  const pathname = usePathname();

  return (
    <aside className="flex w-[250px] shrink-0 flex-col border-r border-[var(--border)] bg-[var(--surface)]">
      <div className="flex items-center gap-2.5 px-[18px] py-5 pb-3.5">
        <span className="text-[16px] font-semibold tracking-tight text-[var(--text)]">
          Nectrix
        </span>
      </div>

      <div className="px-3.5 pb-3">
        <span className="inline-flex items-center gap-1.5 rounded-full bg-[var(--accent-2)] px-2.5 py-1">
          <span className="h-1.5 w-1.5 rounded-full bg-[var(--accent)]" />
          <span className="text-[11.5px] font-semibold tracking-wide text-[var(--accent)]">
            Admin Portal
          </span>
        </span>
      </div>

      <nav className="flex flex-1 flex-col gap-0.5 overflow-y-auto px-3 pb-3">
        {NAV_ITEMS.map((item) => {
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
