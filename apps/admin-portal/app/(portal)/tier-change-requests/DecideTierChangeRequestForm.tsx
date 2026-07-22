"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { approveTierChangeRequestAction, rejectTierChangeRequestAction } from "./actions";

/** TICKET-122 — ADMIN+SUPER_ADMIN only (see the [id] page's own role-conditional-render). */
export function DecideTierChangeRequestForm({ id }: { id: string }) {
  const router = useRouter();
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | undefined>();
  const [decision, setDecision] = useState<"approve" | "reject" | undefined>();
  const [pending, startTransition] = useTransition();

  function submit(next: "approve" | "reject") {
    setError(undefined);
    startTransition(async () => {
      const action = next === "approve" ? approveTierChangeRequestAction : rejectTierChangeRequestAction;
      const result = await action(id, reason);
      if (result.error) {
        setError(result.error);
        return;
      }
      setDecision(next);
      router.refresh();
    });
  }

  if (decision) {
    return (
      <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5 text-[13.5px] text-[var(--pos)]">
        Request {decision === "approve" ? "approved" : "rejected"}.
      </div>
    );
  }

  return (
    <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5">
      <div className="mb-4 text-[14px] font-semibold text-[var(--text)]">Decide this request</div>

      <label className="flex flex-col gap-1.5">
        <span className="text-[12.5px] font-medium text-[var(--text-2)]">
          Reason (shown to the user if rejected)
        </span>
        <textarea
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          rows={3}
          className="rounded-[10px] border border-[var(--border)] bg-transparent px-3 py-2 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
        />
      </label>

      <div className="mt-4 flex gap-2.5">
        <button
          type="button"
          disabled={pending}
          onClick={() => submit("approve")}
          className="h-10 rounded-[11px] bg-[var(--accent)] px-5 text-[13.5px] font-semibold text-white transition-opacity disabled:opacity-60"
        >
          {pending ? "Submitting…" : "Approve"}
        </button>
        <button
          type="button"
          disabled={pending}
          onClick={() => submit("reject")}
          className="h-10 rounded-[11px] border border-[var(--border)] px-5 text-[13.5px] font-semibold text-[var(--neg)] transition-colors hover:bg-[var(--neg)]/8 disabled:opacity-60"
        >
          {pending ? "Submitting…" : "Reject"}
        </button>
      </div>

      {error && <p className="mt-3 text-[12.5px] text-[var(--neg)]">{error}</p>}
    </div>
  );
}
