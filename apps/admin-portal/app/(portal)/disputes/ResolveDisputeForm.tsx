"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import type { FeeLedgerResolution } from "@nectrix/api-client";
import { resolveDisputeAction } from "./actions";

/** TICKET-117 — ADMIN-only (see UserDetailPage's own role-conditional-render precedent). */
export function ResolveDisputeForm({ ledgerId }: { ledgerId: string }) {
  const router = useRouter();
  const [resolution, setResolution] = useState<FeeLedgerResolution>("UPHOLD");
  const [note, setNote] = useState("");
  const [adjustedAmount, setAdjustedAmount] = useState("");
  const [error, setError] = useState<string | undefined>();
  const [resolved, setResolved] = useState(false);
  const [pending, startTransition] = useTransition();

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(undefined);
    startTransition(async () => {
      const result = await resolveDisputeAction(ledgerId, {
        resolution,
        note,
        adjustedAmount:
          resolution === "ADJUST" && adjustedAmount ? Number(adjustedAmount) : undefined,
      });
      if (result.error) {
        setError(result.error);
        return;
      }
      // TICKET-117 bugfix — real, immediate confirmation before the parent Server Component's
      // own router.refresh()-triggered re-render lands (which is what actually removes this form
      // once ledger.status is no longer DISPUTED) — the prior version gave zero feedback here,
      // which is why repeated clicks used to insert multiple resolution rows for one dispute.
      setResolved(true);
      router.refresh();
    });
  }

  if (resolved) {
    return (
      <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5 text-[13.5px] text-[var(--pos)]">
        Resolution submitted ({resolution.toLowerCase()}).
      </div>
    );
  }

  return (
    <form
      onSubmit={submit}
      className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5"
    >
      <div className="mb-4 text-[14px] font-semibold text-[var(--text)]">Resolve this dispute</div>

      <div className="flex flex-wrap gap-4">
        {(["UPHOLD", "ADJUST", "VOID"] as const).map((option) => (
          <label key={option} className="flex items-center gap-2 text-[13.5px] text-[var(--text)]">
            <input
              type="radio"
              name="resolution"
              value={option}
              checked={resolution === option}
              onChange={() => setResolution(option)}
            />
            {option === "UPHOLD" ? "Uphold" : option === "ADJUST" ? "Adjust" : "Void"}
          </label>
        ))}
      </div>

      {resolution === "ADJUST" && (
        <label className="mt-3 flex max-w-[220px] flex-col gap-1.5">
          <span className="text-[12.5px] font-medium text-[var(--text-2)]">
            Adjusted fee amount
          </span>
          <input
            type="number"
            step="0.01"
            value={adjustedAmount}
            onChange={(e) => setAdjustedAmount(e.target.value)}
            required
            className="h-10 rounded-[10px] border border-[var(--border)] bg-transparent px-3 font-mono text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          />
        </label>
      )}

      <label className="mt-3 flex flex-col gap-1.5">
        <span className="text-[12.5px] font-medium text-[var(--text-2)]">Note</span>
        <textarea
          value={note}
          onChange={(e) => setNote(e.target.value)}
          required
          rows={3}
          className="rounded-[10px] border border-[var(--border)] bg-transparent px-3 py-2 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
        />
      </label>

      <button
        type="submit"
        disabled={pending}
        className="mt-4 h-10 rounded-[11px] bg-[var(--accent)] px-5 text-[13.5px] font-semibold text-white transition-opacity disabled:opacity-60"
      >
        {pending ? "Resolving…" : "Submit resolution"}
      </button>

      {error && <p className="mt-3 text-[12.5px] text-[var(--neg)]">{error}</p>}
    </form>
  );
}
