import { requireSession } from "@/lib/auth";

const SAMPLE_CLAUSES = [
  "Your strategy trades according to the risk profile described in your Master profile. Expect drawdowns consistent with that profile during normal operation.",
  "A minimum account equity is recommended so position sizing mirrors correctly at a 1:1 copy ratio.",
  "You may adjust open risk during high-impact news events. Followers accept that mirrored exposure can change intraday.",
  "Your performance fee applies to net new profit, billed on the profit-only, high-water-mark basis described in the Nectrix platform terms.",
];

/**
 * TICKET-116 — mirrors Nectrix.dc.html's `MASTER · TERMS & CONDITIONS` (`vMasterTerms`, `:1125-
 * 1153`). No ticket built real acceptance-tracking for Master-authored clauses (TICKET-111/122's
 * own concern if/when revisited) — static placeholder content matching the mock's own sample
 * clauses, primary actions disabled with a tooltip, same "rendered but inert" precedent
 * app/masters/page.tsx already established.
 */
export default async function MasterTermsPage() {
  await requireSession();

  return (
    <div className="mx-auto max-w-[820px]">
      <div className="mb-6">
        <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">
          Terms &amp; Conditions
        </h1>
        <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
          Set additional terms specific to your strategy. Followers must accept these — alongside
          the Nectrix platform terms — before they can start copying.
        </p>
      </div>

      <div className="mb-4 flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-4.5">
        <div>
          <div className="text-[14px] font-semibold text-[var(--text)]">
            Require acceptance before copying
          </div>
          <div className="mt-0.5 text-[12.5px] text-[var(--text-3)]">
            New followers are blocked from going live until they accept.
          </div>
        </div>
        <button
          type="button"
          disabled
          title="Acceptance tracking isn't available yet"
          className="h-6 w-[42px] shrink-0 cursor-not-allowed rounded-full bg-[var(--accent)] opacity-60"
        >
          <span className="block h-5 w-5 translate-x-[20px] rounded-full bg-white" />
        </button>
      </div>

      <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5.5">
        <div className="mb-1 text-[14px] font-semibold text-[var(--text)]">
          Your additional clauses
        </div>
        <p className="mb-4 text-[12.5px] text-[var(--text-3)]">
          Plain-language terms shown to prospective followers during onboarding.
        </p>
        <div className="flex flex-col gap-3">
          {SAMPLE_CLAUSES.map((clause, i) => (
            <div key={i} className="flex items-start gap-3">
              <div className="mt-0.5 flex h-[26px] w-[26px] shrink-0 items-center justify-center rounded-[7px] bg-[var(--accent-2)] font-mono text-[12px] font-semibold text-[var(--accent)]">
                {i + 1}
              </div>
              <textarea
                readOnly
                disabled
                value={clause}
                title="Editing clauses isn't available yet"
                className="min-h-[56px] flex-1 cursor-not-allowed resize-y rounded-[10px] border border-[var(--border)] bg-[var(--surface-2)] p-3 text-[12.5px] leading-relaxed text-[var(--text)] outline-none"
              />
            </div>
          ))}
        </div>
        <div className="mt-4 flex flex-wrap gap-2.5">
          <button
            type="button"
            disabled
            title="Adding clauses isn't available yet"
            className="h-9 cursor-not-allowed rounded-[10px] border border-dashed border-[var(--border)] px-4 text-[12.5px] font-semibold text-[var(--text-2)]"
          >
            + Add clause
          </button>
          <button
            type="button"
            disabled
            title="Saving terms isn't available yet"
            className="h-9 cursor-not-allowed rounded-[10px] bg-[var(--accent)] px-4 text-[13px] font-semibold text-white opacity-60"
          >
            Save terms
          </button>
        </div>
      </div>
    </div>
  );
}
