"use client";

import { useState, useTransition } from "react";
import type { BrokerAccountSummary } from "@nectrix/api-client";
import { startCopyingAction } from "./actions";

export function StartCopyingForm({
  invitationId,
  brokerAccounts,
}: {
  invitationId: string;
  brokerAccounts: BrokerAccountSummary[];
}) {
  const [customize, setCustomize] = useState(false);
  const [error, setError] = useState<string | undefined>();
  const [pending, startTransition] = useTransition();

  function onSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(undefined);
    const formData = new FormData(e.currentTarget);
    startTransition(async () => {
      const result = await startCopyingAction({}, formData);
      if (result?.error) {
        setError(result.error);
      }
      // On success, startCopyingAction redirects server-side.
    });
  }

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-4">
      <input type="hidden" name="invitationId" value={invitationId} />

      <label className="flex flex-col gap-1.5">
        <span className="text-[12.5px] font-medium text-[var(--text-2)]">
          Broker account to copy into
        </span>
        <select
          name="followerBrokerAccountId"
          required
          defaultValue=""
          className="h-11 rounded-[11px] border border-[var(--border)] bg-transparent px-3.5 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
        >
          <option value="" disabled>
            Select a broker account
          </option>
          {brokerAccounts.map((a) => (
            <option key={a.id} value={a.id}>
              {a.displayLabel ?? a.brokerAccountLogin} ({a.brokerType})
            </option>
          ))}
        </select>
      </label>

      <label className="flex items-center gap-2.5 text-[12.5px] text-[var(--text)]">
        <input
          type="checkbox"
          name="customize"
          checked={customize}
          onChange={(e) => setCustomize(e.target.checked)}
          className="h-4 w-4"
        />
        Customize money-management/risk settings instead of using my master&apos;s recommended
        defaults
      </label>

      {customize && (
        <div className="grid grid-cols-1 gap-3 rounded-[12px] border border-[var(--border)] p-4 sm:grid-cols-3">
          <label className="flex flex-col gap-1.5">
            <span className="text-[12px] font-medium text-[var(--text-2)]">Lot multiplier</span>
            <input
              name="multiplier"
              type="number"
              step="0.1"
              min="0"
              placeholder="e.g. 1.0"
              className="h-10 rounded-[9px] border border-[var(--border)] bg-transparent px-3 text-[13px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
            />
          </label>
          <label className="flex flex-col gap-1.5">
            <span className="text-[12px] font-medium text-[var(--text-2)]">Max lot per trade</span>
            <input
              name="maxLotPerTrade"
              type="number"
              step="0.01"
              min="0"
              placeholder="e.g. 1.00"
              className="h-10 rounded-[9px] border border-[var(--border)] bg-transparent px-3 text-[13px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
            />
          </label>
          <label className="flex flex-col gap-1.5">
            <span className="text-[12px] font-medium text-[var(--text-2)]">Max open positions</span>
            <input
              name="maxOpenPositions"
              type="number"
              step="1"
              min="0"
              placeholder="e.g. 5"
              className="h-10 rounded-[9px] border border-[var(--border)] bg-transparent px-3 text-[13px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
            />
          </label>
        </div>
      )}

      {error && <p className="text-[12.5px] text-[var(--neg)]">{error}</p>}

      <button
        type="submit"
        disabled={pending || brokerAccounts.length === 0}
        className="h-11 w-fit rounded-[11px] bg-[var(--accent)] px-5 text-[13.5px] font-semibold text-white transition-opacity hover:opacity-90 disabled:opacity-60"
      >
        {pending ? "Starting…" : "Start copying"}
      </button>
    </form>
  );
}
