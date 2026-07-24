"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { reconnectBrokerAccountAction } from "./actions";

/**
 * Bugfix — the reverse of DisconnectButton: only ever rendered for a DISCONNECTED account (see
 * page.tsx's own gating). No confirm step — unlike Delete, this is fully reversible (just flips
 * status back to PENDING; credentials are untouched).
 */
export function ReconnectButton({ id, label }: { id: string; label: string }) {
  const router = useRouter();
  const [error, setError] = useState<string | undefined>();
  const [pending, startTransition] = useTransition();

  function onClick() {
    setError(undefined);
    startTransition(async () => {
      const result = await reconnectBrokerAccountAction(id);
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
        aria-label={`Reconnect ${label}`}
        className="h-9 rounded-[9px] border border-[var(--border)] px-3 text-[12.5px] font-semibold text-[var(--accent)] transition-colors hover:bg-[var(--accent)]/8 disabled:opacity-60"
      >
        {pending ? "Reconnecting…" : "Reconnect"}
      </button>
      {error && <p className="text-[11.5px] text-[var(--neg)]">{error}</p>}
    </div>
  );
}
