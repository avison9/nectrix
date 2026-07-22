"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { disconnectBrokerAccountAction } from "./actions";

// Bugfix — was window.confirm()-gated. Real browser behavior (Chrome and others): after a page
// triggers a few confirm()/alert() dialogs, the dialog itself grows a "Prevent this page from
// creating additional dialogs" checkbox — checking it makes the browser silently suppress every
// future window.confirm() call on that page, returning false with no dialog shown at all. That
// makes the button look permanently broken with no visible cause (see admin-portal's
// UserActions.tsx for the full write-up of this same fix). In-page confirm state instead — not a
// native dialog, so the browser can never suppress it.
export function DisconnectButton({ id, label }: { id: string; label: string }) {
  const router = useRouter();
  const [error, setError] = useState<string | undefined>();
  const [confirming, setConfirming] = useState(false);
  const [pending, startTransition] = useTransition();

  function onConfirm() {
    setConfirming(false);
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

  if (confirming) {
    return (
      <div className="flex flex-col items-end gap-1.5">
        <p className="max-w-[220px] text-right text-[12px] text-[var(--text-2)]">
          Disconnect {label}? Copying will stop for any relationship using it.
        </p>
        <div className="flex gap-2">
          <button
            type="button"
            disabled={pending}
            onClick={onConfirm}
            className="h-9 rounded-[9px] border border-[var(--border)] px-3 text-[12.5px] font-semibold text-[var(--neg)] transition-colors hover:bg-[var(--neg)]/8 disabled:opacity-60"
          >
            {pending ? "Disconnecting…" : "Yes, disconnect"}
          </button>
          <button
            type="button"
            disabled={pending}
            onClick={() => setConfirming(false)}
            className="h-9 rounded-[9px] border border-[var(--border)] px-3 text-[12.5px] font-semibold text-[var(--text-2)] transition-colors hover:bg-[var(--surface-2)] disabled:opacity-60"
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
      <button
        type="button"
        disabled={pending}
        onClick={() => setConfirming(true)}
        className="h-9 rounded-[9px] border border-[var(--border)] px-3 text-[12.5px] font-semibold text-[var(--text)] transition-colors hover:bg-[var(--surface-2)] disabled:opacity-60"
      >
        {pending ? "Disconnecting…" : "Disconnect"}
      </button>
      {error && <p className="text-[11.5px] text-[var(--neg)]">{error}</p>}
    </div>
  );
}
