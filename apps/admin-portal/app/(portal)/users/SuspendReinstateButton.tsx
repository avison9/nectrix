"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { reinstateUserAction, suspendUserAction } from "./actions";

/**
 * TICKET-117 — reused on both the /users list row and the /users/[id] detail page. Only ever
 * rendered for an ADMIN caller (SUPPORT doesn't see it at all — see UsersPage/UserDetailPage's
 * own role check), but the real gate is still core-app's server-side @PreAuthorize; this just
 * avoids a guaranteed-403 click.
 */
export function SuspendReinstateButton({
  userId,
  status,
}: {
  userId: string;
  status: string;
}) {
  const router = useRouter();
  const [error, setError] = useState<string | undefined>();
  const [pending, startTransition] = useTransition();

  const isActive = status === "ACTIVE";

  function onClick() {
    const action = isActive ? "Suspend" : "Reinstate";
    if (!window.confirm(`${action} this account?`)) {
      return;
    }
    setError(undefined);
    startTransition(async () => {
      const result = isActive
        ? await suspendUserAction(userId)
        : await reinstateUserAction(userId);
      if (result.error) {
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
        className={`h-9 rounded-[9px] border border-[var(--border)] px-3 text-[12.5px] font-semibold transition-colors disabled:opacity-60 ${
          isActive
            ? "text-[var(--neg)] hover:bg-[var(--neg)]/8"
            : "text-[var(--pos)] hover:bg-[var(--pos)]/8"
        }`}
      >
        {pending ? "Working…" : isActive ? "Suspend" : "Reinstate"}
      </button>
      {error && <p className="text-[11.5px] text-[var(--neg)]">{error}</p>}
    </div>
  );
}
