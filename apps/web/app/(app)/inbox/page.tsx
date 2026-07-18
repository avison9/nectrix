import { requireSession } from "@/lib/auth";

const SAMPLE_MESSAGES = [
  {
    fi: "AR",
    follower: "Amelia Ross",
    time: "2h ago",
    prospect: "Chris Yang",
    email: "chris.yang@email.com",
    note: "Colleague, been trading forex for 2 years, wants to try copy trading.",
    isNew: true,
  },
  {
    fi: "JK",
    follower: "Jamal Khan",
    time: "1d ago",
    prospect: "Nadia Farouk",
    email: "n.farouk@email.com",
    note: "My sister — interested but new to trading, wants a lower-risk profile.",
    isNew: false,
  },
];

/**
 * Mirrors Nectrix.dc.html's `MASTER · INBOX` (`vMasterInbox`, `:784-859`) — the mock's own copy
 * makes clear this is the referral-nomination queue feeding Nectrix Referrals ("Referral requests
 * from your followers... send them an official invite"), a distinct concept from the already-real
 * `/notifications` bell. Phase-2-adjacent (TICKET-207) — no backend exists yet. Full placeholder,
 * mock's own sample messages, "Send invite"/"Dismiss" actions disabled.
 */
export default async function MasterInboxPage() {
  await requireSession();

  return (
    <div className="mx-auto max-w-[900px]">
      <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">Inbox</h1>
          <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
            Referral requests from your followers. When a follower nominates a prospect, send them an
            official invite to link an account.
          </p>
        </div>
        <span className="rounded-full bg-[var(--accent-2)] px-3 py-1.5 text-[12.5px] font-semibold text-[var(--accent)]">
          {SAMPLE_MESSAGES.filter((m) => m.isNew).length} awaiting invite
        </span>
      </div>

      <p
        className="mb-4 text-[12.5px] text-[var(--text-3)]"
        title="Referral nomination tracking is Phase 2 (TICKET-207) — not built yet"
      >
        Showing sample messages — this inbox isn&apos;t wired up to real referral nominations yet.
      </p>

      <div className="flex flex-col gap-3 opacity-70">
        {SAMPLE_MESSAGES.map((m) => (
          <div
            key={m.email}
            className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-4.5"
          >
            <div className="flex items-start gap-3.5">
              <div className="flex h-[38px] w-[38px] shrink-0 items-center justify-center rounded-full bg-[var(--accent-2)] text-[13px] font-semibold text-[var(--accent)]">
                {m.fi}
              </div>
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-[14px] font-semibold text-[var(--text)]">{m.follower}</span>
                  <span className="text-[12.5px] text-[var(--text-3)]">
                    referred a prospect · {m.time}
                  </span>
                </div>
                <div className="mt-3 rounded-[11px] bg-[var(--surface-2)] p-3.5">
                  <div className="flex flex-wrap items-baseline gap-2">
                    <span className="text-[13.5px] font-semibold text-[var(--text)]">
                      {m.prospect}
                    </span>
                    <span className="font-mono text-[12.5px] text-[var(--text-2)]">{m.email}</span>
                  </div>
                  <p className="mt-2 text-[13px] leading-[1.55] text-[var(--text-2)]">{m.note}</p>
                </div>
                <div className="mt-3.5 flex flex-wrap gap-2">
                  <button
                    type="button"
                    disabled
                    title="Referral nomination tracking is Phase 2 (TICKET-207) — not built yet"
                    className="flex h-[38px] cursor-not-allowed items-center gap-1.5 rounded-[10px] bg-[var(--accent)] px-4 text-[13px] font-semibold text-white opacity-60"
                  >
                    Send invite
                  </button>
                  <button
                    type="button"
                    disabled
                    className="h-[38px] cursor-not-allowed rounded-[10px] border border-[var(--border)] px-3.5 text-[13px] font-semibold text-[var(--text-2)] opacity-60"
                  >
                    Dismiss
                  </button>
                </div>
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
