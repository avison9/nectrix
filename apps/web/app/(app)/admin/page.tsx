import { redirect } from "next/navigation";
import { requireSession } from "@/lib/auth";

const SAMPLE_MASTERS = [
  { initials: "MX", name: "Marcus Klein", email: "marcus@apexfx.com", followers: "128", status: "Active" },
  { initials: "SR", name: "Sofia Reyes", email: "sofia@bluewave.trade", followers: "76", status: "Active" },
  { initials: "DK", name: "Daniel Kim", email: "d.kim@northstar.fx", followers: "12", status: "Suspended" },
];

const SAMPLE_FOLLOWERS = [
  { initials: "AR", name: "Amelia Ross", email: "amelia.ross@email.com", master: "Marcus Klein", status: "Active" },
  { initials: "JK", name: "Jamal Khan", email: "jkhan@email.com", master: "Sofia Reyes", status: "Active" },
];

/**
 * Mirrors Nectrix.dc.html's `MASTER · ADMIN` (`vMasterAdmin`, `:1046-1123`) — placed in the mock's
 * MASTER_NAV array, but its own copy is explicit: "Super-admin only. Invite new masters to the
 * platform." Real invite-master / suspend-account actions already exist for real in
 * apps/admin-portal's Users page (TICKET-117) — this is a placeholder mirror inside apps/web (so a
 * SUPER_ADMIN account doesn't see a dead nav item here), not a second real implementation. Gated
 * server-side, not just hidden-by-nav (see Sidebar.tsx's own comment on why this item is appended
 * regardless of Master/Follower branch).
 */
export default async function AdminPage() {
  const { session } = await requireSession();
  if (!session.roles.includes("SUPER_ADMIN")) {
    redirect("/dashboard");
  }

  return (
    <div className="mx-auto max-w-[1000px]">
      <div className="mb-6">
        <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">
          Admin · master management
        </h1>
        <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
          Super-admin only. Invite new masters to the platform — there is no open registration.
        </p>
      </div>

      <p
        className="mb-4 text-[12.5px] text-[var(--text-3)]"
        title="This is a placeholder mirror — manage masters/followers for real in apps/admin-portal's Users page"
      >
        Showing sample data. For real suspend/deactivate/remove actions today, use the Admin Portal
        app&apos;s Users page.
      </p>

      <div className="mb-4 rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5.5 opacity-70">
        <div className="mb-3 text-[13px] font-medium text-[var(--text-2)]">Invite a new master</div>
        <div className="flex flex-wrap gap-2.5">
          <input
            disabled
            placeholder="Full name"
            className="h-[42px] min-w-[160px] flex-1 rounded-[11px] border border-[var(--border)] bg-transparent px-3.5 text-[13.5px] text-[var(--text)] outline-none"
          />
          <input
            disabled
            placeholder="email@company.com"
            className="h-[42px] min-w-[180px] flex-1 rounded-[11px] border border-[var(--border)] bg-transparent px-3.5 text-[13.5px] text-[var(--text)] outline-none"
          />
          <button
            type="button"
            disabled
            title="This is a placeholder — invite masters for real from apps/admin-portal's Users page"
            className="h-[42px] shrink-0 cursor-not-allowed rounded-[11px] bg-[var(--accent)] px-5 text-[13.5px] font-semibold text-white opacity-60"
          >
            Send master invite
          </button>
        </div>
      </div>

      <div className="mb-4 overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)] opacity-70">
        <div className="border-b border-[var(--border)] px-5 py-3.5 text-[14px] font-semibold text-[var(--text)]">
          Masters on the platform
        </div>
        <div className="flex flex-col">
          {SAMPLE_MASTERS.map((r) => (
            <div
              key={r.email}
              className="flex flex-wrap items-center gap-3.5 border-t border-[var(--border)] px-5 py-3.5 first:border-t-0"
            >
              <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-[var(--accent-2)] text-[11.5px] font-semibold text-[var(--accent)]">
                {r.initials}
              </div>
              <div className="min-w-[140px] flex-1">
                <div className="text-[13.5px] font-medium text-[var(--text)]">{r.name}</div>
                <div className="truncate text-[12px] text-[var(--text-3)]">{r.email}</div>
              </div>
              <span className="w-16 text-right font-mono text-[12.5px] text-[var(--text-2)]">
                {r.followers} foll.
              </span>
              <span
                className={`rounded-full px-2.5 py-1 text-[12px] font-semibold ${
                  r.status === "Active"
                    ? "bg-[var(--pos)]/15 text-[var(--pos)]"
                    : "bg-[var(--neg)]/15 text-[var(--neg)]"
                }`}
              >
                {r.status}
              </span>
            </div>
          ))}
        </div>
      </div>

      <div className="overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)] opacity-70">
        <div className="flex items-center justify-between border-b border-[var(--border)] px-5 py-3.5">
          <span className="text-[14px] font-semibold text-[var(--text)]">
            Followers on the platform
          </span>
          <span className="text-[12px] text-[var(--text-3)]">
            Suspend, deactivate or remove any account
          </span>
        </div>
        <div className="flex flex-col">
          {SAMPLE_FOLLOWERS.map((r) => (
            <div
              key={r.email}
              className="flex flex-wrap items-center gap-3.5 border-t border-[var(--border)] px-5 py-3.5 first:border-t-0"
            >
              <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-[var(--surface-2)] text-[11.5px] font-semibold text-[var(--text-2)]">
                {r.initials}
              </div>
              <div className="min-w-[140px] flex-1">
                <div className="text-[13.5px] font-medium text-[var(--text)]">{r.name}</div>
                <div className="truncate text-[12px] text-[var(--text-3)]">{r.email}</div>
              </div>
              <span className="whitespace-nowrap text-[12.5px] text-[var(--text-2)]">
                Copies {r.master}
              </span>
              <span className="rounded-full bg-[var(--pos)]/15 px-2.5 py-1 text-[12px] font-semibold text-[var(--pos)]">
                {r.status}
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
