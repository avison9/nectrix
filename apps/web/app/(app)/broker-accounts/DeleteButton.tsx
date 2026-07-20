"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { deleteBrokerAccountAction } from "./actions";

/**
 * TICKET-101 follow-up — only ever rendered once the account is DISCONNECTED (see AccountCard's
 * own gating) — disconnecting first is mandatory, not just a UI nicety (the backend rejects
 * delete on a still-connected account with 409 too, see BrokerAccountService's own Javadoc).
 */
export function DeleteButton({ id, label }: { id: string; label: string }) {
  const router = useRouter();
  const [error, setError] = useState<string | undefined>();
  const [pending, startTransition] = useTransition();

  function onClick() {
    if (!window.confirm(`Permanently delete ${label}? This can't be undone.`)) {
      return;
    }
    setError(undefined);
    startTransition(async () => {
      const result = await deleteBrokerAccountAction(id);
      if ("error" in result) {
        setError(result.error);
        return;
      }
      router.refresh();
    });
  }

  return (
    <div className="flex flex-col items-end gap-1">
      <button
        type="button"
        disabled={pending}
        onClick={onClick}
        className="h-9 rounded-[9px] border border-[var(--border)] px-3 text-[12.5px] font-semibold text-[var(--neg)] transition-colors hover:bg-[var(--neg)]/8 disabled:opacity-60"
      >
        {pending ? "Deleting…" : "Delete"}
      </button>
      {error && <p className="text-[11.5px] text-[var(--neg)]">{error}</p>}
    </div>
  );
}
