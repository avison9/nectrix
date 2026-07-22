"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { dismissNominationAction, sendInviteForNominationAction } from "./actions";

export function NominationActions({
  nominationId,
  prospectEmail,
}: {
  nominationId: string;
  prospectEmail: string;
}) {
  const router = useRouter();
  const [error, setError] = useState<string | undefined>();
  const [pending, startTransition] = useTransition();

  function onSendInvite() {
    setError(undefined);
    startTransition(async () => {
      const result = await sendInviteForNominationAction(nominationId, prospectEmail);
      if ("error" in result) {
        setError(result.error);
        return;
      }
      router.refresh();
    });
  }

  function onDismiss() {
    setError(undefined);
    startTransition(async () => {
      const result = await dismissNominationAction(nominationId);
      if ("error" in result) {
        setError(result.error);
        return;
      }
      router.refresh();
    });
  }

  return (
    <div className="mt-3.5 flex flex-col gap-2">
      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          disabled={pending}
          onClick={onSendInvite}
          className="flex h-[38px] items-center gap-1.5 rounded-[10px] bg-[var(--accent)] px-4 text-[13px] font-semibold text-white transition-opacity hover:opacity-90 disabled:opacity-60"
        >
          {pending ? "Working…" : "Send invite"}
        </button>
        <button
          type="button"
          disabled={pending}
          onClick={onDismiss}
          className="h-[38px] rounded-[10px] border border-[var(--border)] px-3.5 text-[13px] font-semibold text-[var(--text-2)] transition-colors hover:bg-[var(--surface-2)] disabled:opacity-60"
        >
          Dismiss
        </button>
      </div>
      {error && <p className="text-[12px] text-[var(--neg)]">{error}</p>}
    </div>
  );
}
