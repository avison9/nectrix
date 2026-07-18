import { requireSession } from "@/lib/auth";

const SAMPLE_STATS = [
  { label: "Referred followers", value: "34" },
  { label: "Rewards paid", value: "$1,860" },
  { label: "Pending", value: "6" },
  { label: "Avg. reward", value: "$54.70" },
];

const SAMPLE_ROWS = [
  { initials: "AR", name: "Amelia Ross", joined: "Jun 2", reward: "$62", status: "Paid", tone: "pos" },
  { initials: "JK", name: "Jamal Khan", joined: "May 28", reward: "$41", status: "Paid", tone: "pos" },
  { initials: "PN", name: "Priya Nair", joined: "May 20", reward: "$0", status: "Pending", tone: "muted" },
];

const TONE_CLASSES: Record<string, string> = {
  pos: "bg-[var(--pos)]/15 text-[var(--pos)]",
  muted: "bg-[var(--surface-2)] text-[var(--text-2)]",
};

/**
 * Mirrors Nectrix.dc.html's `MASTER · NECTRIX REFERRALS` (`vMasterNectrix`, `:720-782`). Phase 2
 * (TICKET-207) — the earlier TICKET-116 plan deliberately skipped this page entirely rather than
 * building even a placeholder; that call is reversed here because the user explicitly named this
 * page when reporting the nav gap. No backend exists at all yet (no referral-tracking tables, no
 * reward computation) — full placeholder, mock's own sample figures, primary actions disabled.
 */
export default async function MasterReferralsPage() {
  await requireSession();

  return (
    <div className="mx-auto max-w-[1000px]">
      <div className="mb-6">
        <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">
          Nectrix referrals
        </h1>
        <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
          Every follower who joins through your Nectrix link counts toward platform rewards —
          separate from broker rebates.
        </p>
      </div>

      <p
        className="mb-4 text-[12.5px] text-[var(--text-3)]"
        title="Referral tracking is Phase 2 (TICKET-207) — not built yet"
      >
        Showing sample data — referral tracking is a Phase 2 feature and isn&apos;t wired up yet.
      </p>

      <div className="mb-4 grid grid-cols-1 gap-3.5 opacity-70 sm:grid-cols-2 lg:grid-cols-4">
        {SAMPLE_STATS.map((s) => (
          <div key={s.label} className="rounded-[14px] border border-[var(--border)] bg-[var(--surface)] p-4">
            <div className="text-[12px] font-medium text-[var(--text-2)]">{s.label}</div>
            <div className="mt-2 font-mono text-[24px] font-semibold tracking-tight text-[var(--text)]">
              {s.value}
            </div>
          </div>
        ))}
      </div>

      <div className="mb-4 flex flex-wrap items-center justify-between gap-4 rounded-2xl bg-gradient-to-br from-[var(--accent)] to-[var(--accent)]/70 p-5.5 opacity-70">
        <div>
          <div className="text-[12.5px] font-medium text-white/80">Your Nectrix referral link</div>
          <div className="mt-1.5 font-mono text-[17px] font-semibold text-white">
            nectrix.app/r/your-handle
          </div>
        </div>
        <button
          type="button"
          disabled
          title="Referral tracking is Phase 2 (TICKET-207) — not built yet"
          className="h-[42px] cursor-not-allowed rounded-[11px] bg-white px-5 text-[13.5px] font-semibold text-[var(--accent)] opacity-80"
        >
          Copy link
        </button>
      </div>

      <div className="overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)] opacity-70">
        <div className="border-b border-[var(--border)] px-5 py-3.5 text-[14px] font-semibold text-[var(--text)]">
          Referred followers
        </div>
        <div className="flex flex-col">
          {SAMPLE_ROWS.map((r) => (
            <div
              key={r.name}
              className="flex items-center gap-3 border-t border-[var(--border)] px-5 py-3.5 first:border-t-0"
            >
              <div className="flex h-[30px] w-[30px] shrink-0 items-center justify-center rounded-full bg-[var(--accent-2)] text-[11px] font-semibold text-[var(--accent)]">
                {r.initials}
              </div>
              <span className="min-w-0 flex-1 text-[13.5px] font-medium text-[var(--text)]">
                {r.name}
              </span>
              <span className="whitespace-nowrap text-[12.5px] text-[var(--text-3)]">
                Joined {r.joined}
              </span>
              <span className="w-14 text-right font-mono text-[13px] font-semibold text-[var(--text)]">
                {r.reward}
              </span>
              <span className={`rounded-full px-2.5 py-1 text-[12px] font-semibold ${TONE_CLASSES[r.tone]}`}>
                {r.status}
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
