"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { deactivateBrokerIbLinkAction } from "./actions";

/**
 * Deliberately never a delete button — see BrokerIbLinkRepository#deactivate's own Javadoc
 * (docs/05-domain-model.md §5.7's historical-accuracy invariant: existing `broker_accounts` rows
 * that recorded this link stay unaffected).
 */
export function DeactivateIbLinkButton({ id }: { id: string }) {
  const router = useRouter();
  const [error, setError] = useState<string | undefined>();
  const [pending, startTransition] = useTransition();

  function onClick() {
    if (
      !window.confirm(
        "Deactivate this IB link? It will stop appearing as an option for new invitations — existing accounts opened via it are unaffected.",
      )
    ) {
      return;
    }
    setError(undefined);
    startTransition(async () => {
      const result = await deactivateBrokerIbLinkAction(id);
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
        className="shrink-0 rounded-full border border-[var(--border)] px-2.5 py-1 text-[11.5px] font-semibold text-[var(--text-2)] transition-colors hover:bg-[var(--surface-2)] disabled:opacity-60"
      >
        {pending ? "Deactivating…" : "Deactivate"}
      </button>
      {error && <p className="text-[11px] text-[var(--neg)]">{error}</p>}
    </div>
  );
}
