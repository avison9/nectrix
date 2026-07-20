"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { disconnectBrokerAccountAction } from "./actions";

export function DisconnectButton({ id, label }: { id: string; label: string }) {
  const router = useRouter();
  const [error, setError] = useState<string | undefined>();
  const [pending, startTransition] = useTransition();

  function onClick() {
    if (!window.confirm(`Disconnect ${label}? Copying will stop for any relationship using it.`)) {
      return;
    }
    setError(undefined);
    startTransition(async () => {
      const result = await disconnectBrokerAccountAction(id);
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
        className="h-9 rounded-[9px] border border-[var(--border)] px-3 text-[12.5px] font-semibold text-[var(--text)] transition-colors hover:bg-[var(--surface-2)] disabled:opacity-60"
      >
        {pending ? "Disconnecting…" : "Disconnect"}
      </button>
      {error && <p className="text-[11.5px] text-[var(--neg)]">{error}</p>}
    </div>
  );
}
