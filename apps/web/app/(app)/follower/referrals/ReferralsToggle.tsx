"use client";

import { useState, type ReactNode } from "react";

/**
 * Switches between the real nomination-status view and the (still-placeholder) earnings view —
 * both are pre-rendered server-side and passed in as props, so toggling is pure client-side
 * show/hide, no re-fetch.
 */
export function ReferralsToggle({
  mineContent,
  earningsContent,
}: {
  mineContent: ReactNode;
  earningsContent: ReactNode;
}) {
  const [tab, setTab] = useState<"mine" | "earnings">("mine");

  return (
    <div>
      <div className="mb-4 inline-flex rounded-[11px] border border-[var(--border)] bg-[var(--surface)] p-1">
        <button
          type="button"
          onClick={() => setTab("mine")}
          className={`rounded-[8px] px-4 py-1.5 text-[13px] font-semibold transition-colors ${
            tab === "mine"
              ? "bg-[var(--accent)] text-white"
              : "text-[var(--text-2)] hover:text-[var(--text)]"
          }`}
        >
          Your referrals
        </button>
        <button
          type="button"
          onClick={() => setTab("earnings")}
          className={`rounded-[8px] px-4 py-1.5 text-[13px] font-semibold transition-colors ${
            tab === "earnings"
              ? "bg-[var(--accent)] text-white"
              : "text-[var(--text-2)] hover:text-[var(--text)]"
          }`}
        >
          Referral earnings
        </button>
      </div>
      {tab === "mine" ? mineContent : earningsContent}
    </div>
  );
}
