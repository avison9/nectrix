import { requireSession } from "@/lib/auth";

const SAMPLE_STATS = [
  { label: "Referrals sent", value: "4" },
  { label: "Joined", value: "2" },
  { label: "Earned this month", value: "$38" },
  { label: "Rebate rate", value: "3.0%" },
];

const SAMPLE_ROWS = [
  { email: "chris.yang@email.com", date: "Jun 12", rebate: "+$22", status: "Paid", tone: "pos" },
  { email: "n.farouk@email.com", date: "May 30", rebate: "+$16", status: "Paid", tone: "pos" },
  { email: "sam.lowe@email.com", date: "May 18", rebate: "$0", status: "Pending", tone: "muted" },
];

const TONE_CLASSES: Record<string, string> = {
  pos: "text-[var(--pos)]",
  muted: "text-[var(--text-3)]",
};

/**
 * Mirrors Nectrix.dc.html's `FOLLOWER · REFERRALS` (`vFollowerReferral`, `:1517-1565`). Phase 2
 * (TICKET-207), same reversal-of-scope reasoning as the Master-side `/referrals` placeholder — no
 * backend exists yet. Full placeholder, mock's own sample figures, primary action disabled.
 */
export default async function FollowerReferralsPage() {
  await requireSession();

  return (
    <div className="mx-auto max-w-[1000px]">
      <div className="mb-6">
        <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">Refer &amp; earn</h1>
        <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
          Refer other traders and earn a rebate on every fee their account generates. Submit a
          prospect&apos;s email — your master sends the official invite.
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

      <div className="mb-4 grid grid-cols-1 gap-4 opacity-70 lg:grid-cols-[1.3fr_1fr]">
        <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5.5">
          <div className="mb-3 text-[13px] font-medium text-[var(--text-2)]">
            Refer a trader by email
          </div>
          <div className="flex flex-wrap gap-2.5">
            <input
              disabled
              placeholder="prospect@email.com"
              className="h-11 min-w-[200px] flex-1 rounded-[11px] border border-[var(--border)] bg-transparent px-3.5 text-[13.5px] text-[var(--text)] outline-none"
            />
            <button
              type="button"
              disabled
              title="Referral tracking is Phase 2 (TICKET-207) — not built yet"
              className="h-11 cursor-not-allowed rounded-[11px] bg-[var(--accent)] px-5 text-[13.5px] font-semibold text-white opacity-60"
            >
              Send to my master
            </button>
          </div>
          <p className="mt-3.5 rounded-[11px] bg-[var(--surface-2)] p-3 text-[12.5px] leading-[1.5] text-[var(--text-2)]">
            The email goes to your master to send the invite. When the prospect joins and funds, the
            referral is credited to you.
          </p>
        </div>
        <div className="rounded-2xl bg-[var(--accent-2)] p-5.5">
          <div className="text-[12.5px] font-semibold text-[var(--accent)]">Your rebate rate</div>
          <div className="mt-2 font-mono text-[32px] font-semibold tracking-tight text-[var(--accent)]">
            3.0%
          </div>
          <div className="mt-1 text-[12.5px] leading-[1.5] text-[var(--accent)]">
            of each referred account&apos;s profit.
          </div>
        </div>
      </div>

      <div className="overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)] opacity-70">
        <div className="border-b border-[var(--border)] px-5 py-3.5 text-[14px] font-semibold text-[var(--text)]">
          Your referrals
        </div>
        <div className="flex flex-col">
          {SAMPLE_ROWS.map((r) => (
            <div
              key={r.email}
              className="flex items-center gap-3.5 border-t border-[var(--border)] px-5 py-3.5 first:border-t-0"
            >
              <span className="min-w-0 flex-1 truncate font-mono text-[13px] text-[var(--text)]">
                {r.email}
              </span>
              <span className="whitespace-nowrap text-[12.5px] text-[var(--text-3)]">{r.date}</span>
              <span className={`w-16 text-right font-mono text-[13px] font-semibold ${TONE_CLASSES[r.tone]}`}>
                {r.rebate}
              </span>
              <span className="w-16 text-right text-[12px] text-[var(--text-3)]">{r.status}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
