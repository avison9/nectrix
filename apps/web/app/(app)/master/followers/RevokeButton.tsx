"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { revokeInvitationAction } from "./actions";

export function RevokeButton({ id }: { id: string }) {
  const router = useRouter();
  const [error, setError] = useState<string | undefined>();
  const [pending, startTransition] = useTransition();

  function onClick() {
    if (!window.confirm("Revoke this invitation? The link will stop working immediately.")) {
      return;
    }
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

  return (
    <div className="flex flex-col items-end gap-1">
      <button
        type="button"
        disabled={pending}
        onClick={onClick}
        className="shrink-0 rounded-full border border-[var(--border)] px-2.5 py-1 text-[11.5px] font-semibold text-[var(--text-2)] transition-colors hover:bg-[var(--surface-2)] disabled:opacity-60"
      >
        {pending ? "Revoking…" : "Revoke"}
      </button>
      {error && <p className="text-[11px] text-[var(--neg)]">{error}</p>}
    </div>
  );
}
