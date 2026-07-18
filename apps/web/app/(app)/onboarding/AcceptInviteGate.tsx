"use client";

import { useState } from "react";

const NECTRIX_TERMS = [
  "Nectrix is a technology platform that mirrors trades between accounts. It is not a broker, does not hold client funds, and does not provide investment advice.",
  "Copy trading carries substantial risk. Past performance of any master strategy is not indicative of future results, and you may lose some or all of your capital.",
  "You authorise Nectrix to place mirrored orders on your connected trading account using a read-only investor connection, strictly in line with your chosen copy ratio.",
  "Performance fees are charged on profit only, above the high-water mark, and are settled by your broker. You may pause or disconnect copying at any time.",
  "You confirm you are of legal age in your jurisdiction and that copy trading is permitted where you reside.",
];

// Generic sample clauses, not a real master's terms — no relationship exists yet at this point
// (that's exactly why this gate is showing), so there's no real per-master clause set to pull from
// yet. Real per-relationship clauses are edited for real at Master's own /terms page.
const SAMPLE_MASTER_TERMS = [
  "Your master's strategy trades according to the risk profile described on their public profile. Expect drawdowns consistent with that profile during normal operation.",
  "A minimum account equity is recommended so position sizing mirrors correctly at your chosen copy ratio.",
  "Your master may adjust open risk during high-impact news events. You accept that mirrored exposure can change intraday.",
  "Your performance fee applies to net new profit, billed on the profit-only, high-water-mark basis described in the Nectrix platform terms.",
];

function TermsBox({
  title,
  clauses,
  checked,
  onToggle,
  label,
}: {
  title: string;
  clauses: string[];
  checked: boolean;
  onToggle: () => void;
  label: string;
}) {
  return (
    <div className="overflow-hidden rounded-[12px] border border-[var(--border)]">
      <div className="flex items-center gap-2.5 bg-[var(--surface-2)] px-3.5 py-3">
        <svg viewBox="0 0 24 24" width={15} height={15} fill="none" stroke="var(--text-2)" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round">
          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6" />
        </svg>
        <span className="text-[12.5px] font-semibold text-[var(--text)]">{title}</span>
      </div>
      <div className="flex max-h-[132px] flex-col gap-2.5 overflow-y-auto px-3.5 py-3">
        {clauses.map((c, i) => (
          <p key={i} className="m-0 text-[12px] leading-[1.55] text-[var(--text-2)]">
            {c}
          </p>
        ))}
      </div>
      <button
        type="button"
        onClick={onToggle}
        className="flex w-full items-center gap-2.5 border-t border-[var(--border)] px-3.5 py-3 text-left"
      >
        <span
          className={`flex h-[18px] w-[18px] shrink-0 items-center justify-center rounded-[5px] border ${
            checked ? "border-[var(--accent)] bg-[var(--accent)]" : "border-[var(--border)]"
          }`}
        >
          {checked && (
            <svg viewBox="0 0 24 24" width={13} height={13} fill="none" stroke="#fff" strokeWidth={3} strokeLinecap="round" strokeLinejoin="round">
              <path d="M20 6L9 17l-5-5" />
            </svg>
          )}
        </span>
        <span className="text-[12.5px] text-[var(--text)]">{label}</span>
      </button>
    </div>
  );
}

/**
 * Mirrors Nectrix.dc.html's onboarding accept-gate (`showAcceptGate`, `:1473-1512` in the live
 * design) — the checkbox UI is real and interactive (both boxes must be checked to enable the
 * button), and so is the button itself once enabled: clicking it flips local `accepted` state and
 * shows a confirmation, the same "real interaction, no backend persistence yet" pattern the
 * checkboxes already use — there's no real invitation-acceptance endpoint yet (TICKET-118 hasn't
 * shipped), so nothing round-trips to the server, but the UI itself isn't a dead end. Nectrix
 * platform terms are the real, static platform terms; the master's terms are generic sample clauses
 * (see this file's own comment) since no relationship exists yet to pull real per-master clauses
 * from.
 */
export function AcceptInviteGate() {
  const [acceptedNectrix, setAcceptedNectrix] = useState(false);
  const [acceptedMaster, setAcceptedMaster] = useState(false);
  const [accepted, setAccepted] = useState(false);
  const bothAccepted = acceptedNectrix && acceptedMaster;

  if (accepted) {
    return (
      <div className="mt-3.5 flex items-center gap-2.5 rounded-[10px] bg-[var(--pos)]/10 px-4 py-3 text-[13px] font-medium text-[var(--pos)]">
        <svg viewBox="0 0 24 24" width={16} height={16} fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" strokeLinejoin="round">
          <path d="M20 6L9 17l-5-5" />
        </svg>
        Accepted — this applies once your account is matched with a master.
      </div>
    );
  }

  return (
    <div className="mt-3.5 flex flex-col gap-3">
      <TermsBox
        title="Nectrix platform terms"
        clauses={NECTRIX_TERMS}
        checked={acceptedNectrix}
        onToggle={() => setAcceptedNectrix((v) => !v)}
        label="I have read and accept the Nectrix platform terms"
      />
      <TermsBox
        title="Your master's additional terms"
        clauses={SAMPLE_MASTER_TERMS}
        checked={acceptedMaster}
        onToggle={() => setAcceptedMaster((v) => !v)}
        label="I have read and accept my master's additional terms"
      />
      <button
        type="button"
        disabled={!bothAccepted}
        onClick={() => setAccepted(true)}
        className={`inline-flex h-10 w-fit items-center gap-2 rounded-[10px] px-5 text-[13px] font-semibold transition-opacity ${
          bothAccepted
            ? "bg-[var(--accent)] text-white hover:opacity-90"
            : "cursor-not-allowed border border-[var(--border)] bg-[var(--surface-2)] text-[var(--text-3)]"
        }`}
      >
        {bothAccepted ? "Accept & continue" : "Accept both to continue"}
      </button>
    </div>
  );
}
