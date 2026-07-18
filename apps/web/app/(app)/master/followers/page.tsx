import { requireSession } from "@/lib/auth";

const SAMPLE_STATS = [
  { label: "Invites sent", value: "42" },
  { label: "Accounts opened", value: "31" },
  { label: "Now copying", value: "28" },
  { label: "Conversion", value: "67%" },
];

const SAMPLE_PIPELINE = [
  { who: "amelia.ross@email.com", sent: "2h ago", status: "Copying", tone: "pos" },
  { who: "d.mercer@email.com", sent: "1d ago", status: "Account opened", tone: "accent" },
  { who: "jkhan@email.com", sent: "3d ago", status: "Invited", tone: "muted" },
  { who: "priya.n@email.com", sent: "5d ago", status: "Copying", tone: "pos" },
  { who: "t.oconnor@email.com", sent: "1w ago", status: "Invited", tone: "muted" },
];

const TONE_CLASSES: Record<string, string> = {
  pos: "bg-[var(--pos)]/15 text-[var(--pos)]",
  accent: "bg-[var(--accent-2)] text-[var(--accent)]",
  muted: "bg-[var(--surface-2)] text-[var(--text-2)]",
};

/**
 * Mirrors Nectrix.dc.html's `MASTER · INVITE FOLLOWERS` (`vMasterFollowers`, `:619-676`) — the
 * mock's "Followers" nav item. TICKET-118 (invitation system) hasn't been built anywhere yet (its
 * own ticket text scopes the real UI to apps/admin-portal's "Master-scoped" capabilities, not this
 * app — see this page's sibling comments in Sidebar.tsx). Same "rendered but inert" precedent
 * app/(app)/master/followers/[id]/page.tsx already established: mock's own sample figures, no
 * fetch calls, primary actions disabled with a tooltip.
 */
export default async function MasterFollowersPage() {
  await requireSession();

  return (
    <div className="mx-auto max-w-[900px]">
      <div className="mb-6">
        <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">
          Invite followers
        </h1>
        <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
          Share your invite link. Followers open a broker account under your IB link, then start
          copying automatically.
        </p>
      </div>

      <div className="mb-4 grid grid-cols-1 gap-4 lg:grid-cols-[1.4fr_1fr]">
        <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5.5">
          <div className="mb-3 text-[13px] font-medium text-[var(--text-2)]">
            Your follower invite link
          </div>
          <div className="flex flex-wrap gap-2.5">
            <div className="flex h-11 min-w-[220px] flex-1 items-center rounded-[11px] border border-[var(--border)] bg-[var(--surface-2)] px-3.5 font-mono text-[13px] text-[var(--text)]">
              nectrix.app/join/your-handle
            </div>
            <button
              type="button"
              disabled
              title="Invitations aren't available yet — TICKET-118 hasn't shipped"
              className="h-11 cursor-not-allowed rounded-[11px] bg-[var(--accent)] px-5 text-[13.5px] font-semibold text-white opacity-60"
            >
              Copy link
            </button>
          </div>

          <div className="mt-3.5 border-t border-[var(--border)] pt-3.5">
            <div className="mb-2.5 text-[13px] font-medium text-[var(--text-2)]">
              Or invite by email
            </div>
            <div className="flex flex-wrap gap-2.5">
              <input
                disabled
                placeholder="follower@email.com"
                className="h-[42px] min-w-[200px] flex-1 rounded-[11px] border border-[var(--border)] bg-transparent px-3.5 text-[13.5px] text-[var(--text)] opacity-60 outline-none"
              />
              <button
                type="button"
                disabled
                title="Invitations aren't available yet — TICKET-118 hasn't shipped"
                className="h-[42px] cursor-not-allowed rounded-[11px] border border-[var(--border)] px-4.5 text-[13.5px] font-semibold text-[var(--text)] opacity-60"
              >
                Send invite
              </button>
            </div>
          </div>

          <p
            className="mt-3.5 text-[12.5px] text-[var(--text-3)]"
            title="Invitations aren't available yet — TICKET-118 hasn't shipped"
          >
            Showing sample stats and pipeline below — this page isn&apos;t wired up to real invite
            activity yet.
          </p>
        </div>

        <div className="grid grid-cols-2 gap-3">
          {SAMPLE_STATS.map((s) => (
            <div
              key={s.label}
              className="rounded-[14px] border border-[var(--border)] bg-[var(--surface)] p-4 opacity-70"
            >
              <div className="text-[12px] font-medium text-[var(--text-2)]">{s.label}</div>
              <div className="mt-2 font-mono text-[24px] font-semibold tracking-tight text-[var(--text)]">
                {s.value}
              </div>
            </div>
          ))}
        </div>
      </div>

      <div className="overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)] opacity-70">
        <div className="border-b border-[var(--border)] px-5 py-3.5 text-[14px] font-semibold text-[var(--text)]">
          Invite pipeline
        </div>
        <div className="flex flex-col">
          {SAMPLE_PIPELINE.map((r) => (
            <div
              key={r.who}
              className="flex items-center gap-3.5 border-t border-[var(--border)] px-5 py-3.5 first:border-t-0"
            >
              <span className="min-w-0 flex-1 truncate font-mono text-[13px] text-[var(--text)]">
                {r.who}
              </span>
              <span className="whitespace-nowrap text-[12.5px] text-[var(--text-3)]">{r.sent}</span>
              <span
                className={`rounded-full px-2.5 py-1 text-[12px] font-semibold whitespace-nowrap ${TONE_CLASSES[r.tone]}`}
              >
                {r.status}
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
