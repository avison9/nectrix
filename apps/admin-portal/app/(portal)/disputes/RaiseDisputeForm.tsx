"use client";

import { useActionState } from "react";
import { raiseDisputeAction, type DisputeActionState } from "./actions";

const initialState: DisputeActionState = {};

/**
 * TICKET-117 — the real "raise a dispute" entry point: there's no other fee-ledger-row browsing
 * UI anywhere in admin-portal yet, so a follower/master-initiated dispute is recorded here by
 * ledger ID (visible from the settlement's own audit trail) + a reason, same as the ticket's own
 * plan anticipated for when no single-row admin view is already reachable.
 */
export function RaiseDisputeForm() {
  const [state, formAction, pending] = useActionState(raiseDisputeAction, initialState);

  return (
    <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5">
      <div className="mb-4 text-[14px] font-semibold text-[var(--text)]">Raise a dispute</div>
      <form action={formAction} className="flex flex-wrap items-end gap-3">
        <label className="flex min-w-[280px] flex-1 flex-col gap-1.5">
          <span className="text-[12.5px] font-medium text-[var(--text-2)]">Fee ledger ID</span>
          <input
            name="ledgerId"
            type="text"
            required
            className="h-10 rounded-[10px] border border-[var(--border)] bg-transparent px-3 font-mono text-[12.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          />
        </label>
        <label className="flex min-w-[280px] flex-1 flex-col gap-1.5">
          <span className="text-[12.5px] font-medium text-[var(--text-2)]">Reason</span>
          <input
            name="reason"
            type="text"
            required
            className="h-10 rounded-[10px] border border-[var(--border)] bg-transparent px-3 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          />
        </label>
        <button
          type="submit"
          disabled={pending}
          className="h-10 shrink-0 rounded-[11px] bg-[var(--accent)] px-5 text-[13.5px] font-semibold text-white transition-opacity disabled:opacity-60"
        >
          {pending ? "Raising…" : "Mark as disputed"}
        </button>
      </form>

      {state.error && <p className="mt-3 text-[12.5px] text-[var(--neg)]">{state.error}</p>}
      {state.success && <p className="mt-3 text-[12.5px] text-[var(--pos)]">{state.success}</p>}
    </div>
  );
}
