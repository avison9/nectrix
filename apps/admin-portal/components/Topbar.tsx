import { logoutAction } from "@/app/login/actions";

function initialsFor(email: string): string {
  const local = email.split("@")[0] ?? "";
  const parts = local.split(/[._-]/).filter(Boolean);
  const initials = parts.length >= 2 ? parts[0]![0] + parts[1]![0] : local.slice(0, 2);
  return initials.toUpperCase();
}

export function Topbar({ email, roles }: { email: string; roles: string[] }) {
  return (
    <header className="sticky top-0 z-30 flex h-[58px] shrink-0 items-center gap-3.5 border-b border-[var(--border)] bg-[var(--surface)] px-5">
      <div className="flex-1" />

      <div className="flex items-center gap-2.5">
        <div className="flex h-[30px] w-[30px] items-center justify-center rounded-full border border-[var(--border)] bg-[var(--surface-2)] text-xs font-semibold text-[var(--text-2)]">
          {initialsFor(email)}
        </div>
        <div className="hidden leading-tight sm:block">
          <div className="text-[13px] font-medium text-[var(--text)]">{email}</div>
          <div className="text-[11px] text-[var(--text-3)]">{roles.join(", ")}</div>
        </div>
        <form action={logoutAction}>
          <button
            type="submit"
            className="flex h-9 items-center justify-center rounded-[10px] border border-[var(--border)] px-3 text-[12.5px] font-medium text-[var(--text-2)] transition-colors hover:bg-[var(--surface-2)] hover:text-[var(--text)]"
          >
            Log out
          </button>
        </form>
      </div>
    </header>
  );
}
