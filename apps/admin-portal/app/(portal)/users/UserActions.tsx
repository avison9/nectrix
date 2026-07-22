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
 *
 * Bugfix — this used to gate on {@code window.confirm(...)}. A real, live-verified browser
 * behavior (Chrome and others): after a page has triggered a few {@code confirm()}/{@code
 * alert()} dialogs, the dialog itself grows a "Prevent this page from creating additional
 * dialogs" checkbox — checking it makes the BROWSER silently suppress every future {@code
 * window.confirm()} call on that page for the rest of the tab's life, with {@code confirm()}
 * immediately returning {@code false} and no dialog shown at all. That looked exactly like "the
 * button stopped responding": the onClick handler still fired, but {@code if
 * (!window.confirm(...)) return;} silently took the early-return branch every time afterward, with
 * no visible sign why. A native dialog should never be able to permanently disable a page's own
 * critical actions like this, so the confirm step is now in-page state (below) instead — nothing
 * the browser itself can suppress.
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
  const [confirming, setConfirming] = useState<"suspend-reinstate" | "delete" | null>(null);

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

  function onConfirmSuspendReinstate() {
    setConfirming(null);
    startTransition(async () => {
      const result = isActive
        ? await suspendUserAction(user.id)
        : await reinstateUserAction(user.id);
      applyResult(result);
    });
  }

  function onConfirmDelete() {
    setConfirming(null);
    startTransition(async () => {
      const result = await deleteUserAction(user.id);
      applyResult(result);
    });
  }

  if (user.status === "DELETED") {
    return <span className="text-[12.5px] text-[var(--text-3)]">Deleted</span>;
  }

  if (confirming) {
    const isDelete = confirming === "delete";
    return (
      <div className="flex flex-col items-end gap-1">
        <div className="flex flex-nowrap items-center gap-2">
          <span className="whitespace-nowrap text-[12px] text-[var(--text-2)]">
            {isDelete ? "Delete?" : isActive ? "Suspend?" : "Reinstate?"}
          </span>
          <button
            type="button"
            disabled={pending}
            onClick={isDelete ? onConfirmDelete : onConfirmSuspendReinstate}
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
          onClick={() => setConfirming("suspend-reinstate")}
          className={`h-9 shrink-0 whitespace-nowrap rounded-[9px] border border-[var(--border)] px-3 text-[12.5px] font-semibold transition-colors disabled:opacity-60 ${
            isActive
              ? "text-[var(--neg)] hover:bg-[var(--neg)]/8"
              : "text-[var(--pos)] hover:bg-[var(--pos)]/8"
          }`}
        >
          {isActive ? "Suspend" : "Reinstate"}
        </button>
        <button
          type="button"
          disabled={pending}
          onClick={() => setConfirming("delete")}
          className="h-9 shrink-0 whitespace-nowrap rounded-[9px] border border-[var(--border)] px-3 text-[12.5px] font-semibold text-[var(--neg)] transition-colors hover:bg-[var(--neg)]/8 disabled:opacity-60"
        >
          Delete
        </button>
      </div>
      {error && <p className="text-[11.5px] text-[var(--neg)]">{error}</p>}
    </div>
  );
}
