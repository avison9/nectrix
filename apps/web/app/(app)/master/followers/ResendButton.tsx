"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { resendInvitationAction } from "./actions";

export function ResendButton({ id }: { id: string }) {
  const router = useRouter();
  const [error, setError] = useState<string | undefined>();
  const [pending, startTransition] = useTransition();

  function onClick() {
    setError(undefined);
    startTransition(async () => {
      const result = await resendInvitationAction(id);
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
        {pending ? "Resending…" : "Resend"}
      </button>
      {error && <p className="text-[11px] text-[var(--neg)]">{error}</p>}
    </div>
  );
}
