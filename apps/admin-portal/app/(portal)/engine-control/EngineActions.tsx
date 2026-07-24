"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { restartEngineAction, startEngineAction, stopEngineAction } from "./actions";

type ConfirmAction = "restart" | "stop" | "start";

/**
 * Mirrors apps/admin-portal's UserActions.tsx confirm-inline pattern exactly (local `confirming`
 * state, never `window.confirm` — see that component's own Javadoc for the Chrome
 * dialog-suppression bug this avoids). Only ever rendered for an ADMIN caller (see page.tsx's own
 * isAdmin check) — the real gate is still core-app's server-side @PreAuthorize, this just avoids a
 * guaranteed-403 click.
 */
export function EngineActions({
  serviceId,
  displayName,
}: {
  serviceId: string;
  displayName: string;
}) {
  const router = useRouter();
  const [error, setError] = useState<string | undefined>();
  const [pending, startTransition] = useTransition();
  const [confirming, setConfirming] = useState<ConfirmAction | null>(null);

  function run(action: ConfirmAction) {
    setConfirming(null);
    startTransition(async () => {
      const actionFn =
        action === "restart"
          ? restartEngineAction
          : action === "stop"
            ? stopEngineAction
            : startEngineAction;
      const result = await actionFn(serviceId);
      if (result.error) {
        setError(result.error);
        return;
      }
      setError(undefined);
      router.refresh();
    });
  }

  if (confirming) {
    return (
      <div className="flex flex-col items-end gap-1">
        <div className="flex flex-nowrap items-center gap-2">
          <span className="whitespace-nowrap text-[12px] text-[var(--text-2)]">
            {confirming[0].toUpperCase() + confirming.slice(1)} {displayName}?
          </span>
          <button
            type="button"
            disabled={pending}
            onClick={() => run(confirming)}
            className="h-9 shrink-0 whitespace-nowrap rounded-[9px] border border-[var(--border)] px-3 text-[12.5px] font-semibold text-[var(--neg)] transition-colors hover:bg-[var(--neg)]/8 disabled:opacity-60"
          >
            {pending ? "Working…" : "Yes"}
          </button>
          <button
            type="button"
            disabled={pending}
            onClick={() => setConfirming(null)}
            className="h-9 shrink-0 whitespace-nowrap rounded-[9px] border border-[var(--border)] px-3 text-[12.5px] font-semibold text-[var(--text-2)] transition-colors hover:bg-[var(--surface-2)] disabled:opacity-60"
          >
            Cancel
          </button>
        </div>
        {error && <p className="text-[11.5px] text-[var(--neg)]">{error}</p>}
      </div>
    );
  }

  return (
    <div className="flex flex-col items-end gap-1">
      <div className="flex flex-nowrap gap-2">
        <button
          type="button"
          disabled={pending}
          onClick={() => setConfirming("restart")}
          className="h-9 shrink-0 whitespace-nowrap rounded-[9px] border border-[var(--border)] px-3 text-[12.5px] font-semibold text-[var(--text)] transition-colors hover:bg-[var(--surface-2)] disabled:opacity-60"
        >
          Restart
        </button>
        <button
          type="button"
          disabled={pending}
          onClick={() => setConfirming("stop")}
          className="h-9 shrink-0 whitespace-nowrap rounded-[9px] border border-[var(--border)] px-3 text-[12.5px] font-semibold text-[var(--neg)] transition-colors hover:bg-[var(--neg)]/8 disabled:opacity-60"
        >
          Stop
        </button>
        <button
          type="button"
          disabled={pending}
          onClick={() => setConfirming("start")}
          className="h-9 shrink-0 whitespace-nowrap rounded-[9px] border border-[var(--border)] px-3 text-[12.5px] font-semibold text-[var(--pos)] transition-colors hover:bg-[var(--pos)]/8 disabled:opacity-60"
        >
          Start
        </button>
      </div>
      {error && <p className="text-[11.5px] text-[var(--neg)]">{error}</p>}
    </div>
  );
}
