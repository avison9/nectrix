"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import type { UserSummary } from "@nectrix/api-client";
import { deleteUserAction, reinstateUserAction, suspendUserAction } from "./actions";

/**
 * TICKET-117 — Suspend/Reinstate + Delete, reused on both the /users list row and the /users/[id]
 * detail page. Only ever rendered for an ADMIN caller (SUPPORT doesn't see it at all — see
 * UserSearch/UserDetailPage's own role check), but the real gate is still core-app's server-side
 * @PreAuthorize; this just avoids a guaranteed-403 click.
 *
 * TICKET-117 bugfix — {@code onUpdated} lets a caller holding its own local copy of the user (e.g.
 * UserSearch's results table, a plain useState array that router.refresh() can never reach since
 * it isn't Server Component data) apply the endpoint's own real response immediately. The detail
 * page instead passes nothing and falls back to router.refresh(), which correctly re-fetches real
 * Server Component data there.
 */
export function UserActions({
  user,
  onUpdated,
}: {
  user: UserSummary;
  onUpdated?: (updated: UserSummary) => void;
}) {
  const router = useRouter();
  const [error, setError] = useState<string | undefined>();
  const [pending, startTransition] = useTransition();

  const isActive = user.status === "ACTIVE";

  function applyResult(result: { error?: string; user?: UserSummary }) {
    if (result.error || !result.user) {
      setError(result.error ?? "Something went wrong.");
      return;
    }
    setError(undefined);
    if (onUpdated) {
      onUpdated(result.user);
    } else {
      router.refresh();
    }
  }

  function onSuspendReinstate() {
    const action = isActive ? "Suspend" : "Reinstate";
    if (!window.confirm(`${action} this account?`)) {
      return;
    }
    startTransition(async () => {
      const result = isActive
        ? await suspendUserAction(user.id)
        : await reinstateUserAction(user.id);
      applyResult(result);
    });
  }

  function onDelete() {
    if (
      !window.confirm(
        `Delete ${user.email}? This deactivates the account for good — it can't sign in again.`,
      )
    ) {
      return;
    }
    startTransition(async () => {
      const result = await deleteUserAction(user.id);
      applyResult(result);
    });
  }

  if (user.status === "DELETED") {
    return <span className="text-[12.5px] text-[var(--text-3)]">Deleted</span>;
  }

  return (
    <div className="flex flex-col items-end gap-1">
      <div className="flex gap-2">
        <button
          type="button"
          disabled={pending}
          onClick={onSuspendReinstate}
          className={`h-9 rounded-[9px] border border-[var(--border)] px-3 text-[12.5px] font-semibold transition-colors disabled:opacity-60 ${
            isActive
              ? "text-[var(--neg)] hover:bg-[var(--neg)]/8"
              : "text-[var(--pos)] hover:bg-[var(--pos)]/8"
          }`}
        >
          {pending ? "Working…" : isActive ? "Suspend" : "Reinstate"}
        </button>
        <button
          type="button"
          disabled={pending}
          onClick={onDelete}
          className="h-9 rounded-[9px] border border-[var(--border)] px-3 text-[12.5px] font-semibold text-[var(--neg)] transition-colors hover:bg-[var(--neg)]/8 disabled:opacity-60"
        >
          Delete
        </button>
      </div>
      {error && <p className="text-[11.5px] text-[var(--neg)]">{error}</p>}
    </div>
  );
}
