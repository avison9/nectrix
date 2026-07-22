"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { revokeInvitationAction } from "./actions";

// Bugfix — was window.confirm()-gated. Real browser behavior (Chrome and others): after a page
// triggers a few confirm()/alert() dialogs, the dialog itself grows a "Prevent this page from
// creating additional dialogs" checkbox — checking it makes the browser silently suppress every
// future window.confirm() call on that page, returning false with no dialog shown at all. That
// makes the button look permanently broken with no visible cause (see admin-portal's
// UserActions.tsx for the full write-up of this same fix). In-page confirm state instead — not a
// native dialog, so the browser can never suppress it.
export function RevokeButton({ id }: { id: string }) {
  const router = useRouter();
  const [error, setError] = useState<string | undefined>();
  const [confirming, setConfirming] = useState(false);
  const [pending, startTransition] = useTransition();

  function onConfirm() {
    setConfirming(false);
    setError(undefined);
    startTransition(async () => {
      const result = await revokeInvitationAction(id);
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
        <p className="max-w-[200px] text-right text-[11.5px] text-[var(--text-2)]">
          Revoke this invitation? The link will stop working immediately.
        </p>
        <div className="flex gap-2">
          <button
            type="button"
            disabled={pending}
            onClick={onConfirm}
            className="shrink-0 rounded-full border border-[var(--border)] px-2.5 py-1 text-[11.5px] font-semibold text-[var(--neg)] transition-colors hover:bg-[var(--neg)]/8 disabled:opacity-60"
          >
            {pending ? "Revoking…" : "Yes, revoke"}
          </button>
          <button
            type="button"
            disabled={pending}
            onClick={() => setConfirming(false)}
            className="shrink-0 rounded-full border border-[var(--border)] px-2.5 py-1 text-[11.5px] font-semibold text-[var(--text-2)] transition-colors hover:bg-[var(--surface-2)] disabled:opacity-60"
          >
            Cancel
          </button>
        </div>
        {error && <p className="text-[11px] text-[var(--neg)]">{error}</p>}
      </div>
    );
  }

  return (
    <div className="flex flex-col items-end gap-1">
      <button
        type="button"
        disabled={pending}
        onClick={() => setConfirming(true)}
        className="shrink-0 rounded-full border border-[var(--border)] px-2.5 py-1 text-[11.5px] font-semibold text-[var(--text-2)] transition-colors hover:bg-[var(--surface-2)] disabled:opacity-60"
      >
        {pending ? "Revoking…" : "Revoke"}
      </button>
      {error && <p className="text-[11px] text-[var(--neg)]">{error}</p>}
    </div>
  );
}
