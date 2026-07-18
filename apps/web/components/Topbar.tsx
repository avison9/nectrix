import Link from "next/link";

// TICKET-116 — mirrors apps/admin-portal's own Topbar.tsx, plus the mock's header bell icon
// (translated to a real notification-center link + unread count, not the mock's own master-only
// invite-request Inbox — see TICKET-115's own plan for that distinction). The user avatar/email and
// sign-out button that used to live here moved to Sidebar's own footer nav, matching the mock's
// actual layout (`footNav` + user block live in the sidebar, not the topbar).
export function Topbar({ unreadCount }: { unreadCount: number }) {
  return (
    <header className="sticky top-0 z-30 flex h-[58px] shrink-0 items-center gap-3.5 border-b border-[var(--border)] bg-[var(--surface)] px-5">
      <div className="flex-1" />

      <Link
        href="/notifications"
        aria-label="Notifications"
        className="relative flex h-9 w-9 items-center justify-center rounded-[10px] border border-[var(--border)] text-[var(--text-2)] transition-colors hover:bg-[var(--surface-2)] hover:text-[var(--text)]"
      >
        <svg
          viewBox="0 0 24 24"
          width={17}
          height={17}
          fill="none"
          stroke="currentColor"
          strokeWidth={1.7}
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9M13.73 21a2 2 0 0 1-3.46 0" />
        </svg>
        {unreadCount > 0 && (
          <span className="absolute -right-1 -top-1 flex h-4 min-w-4 items-center justify-center rounded-full bg-[var(--neg)] px-1 text-[10px] font-semibold text-white">
            {unreadCount > 9 ? "9+" : unreadCount}
          </span>
        )}
      </Link>
    </header>
  );
}
