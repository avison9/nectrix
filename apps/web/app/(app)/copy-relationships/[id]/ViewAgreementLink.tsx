"use client";

import { useState, useTransition } from "react";
import { getAgreementUrlAction } from "./actions";

/**
 * TICKET-120 AC2 — fetches a FRESH short-lived signed URL on each click (never pre-rendered
 * server-side and cached in the page — it would go stale before the user gets to it) and opens it
 * in a new tab.
 */
export function ViewAgreementLink({ id }: { id: string }) {
  const [error, setError] = useState<string | undefined>();
  const [pending, startTransition] = useTransition();

  function onClick() {
    setError(undefined);
    startTransition(async () => {
      const result = await getAgreementUrlAction(id);
      if ("error" in result) {
        setError(result.error);
        return;
      }
      window.open(result.documentUrl, "_blank", "noopener,noreferrer");
    });
  }

  return (
    <div className="flex flex-col items-start gap-1">
      <button
        type="button"
        disabled={pending}
        onClick={onClick}
        className="text-[12.5px] font-semibold text-[var(--accent)] underline-offset-2 hover:underline disabled:opacity-60"
      >
        {pending ? "Loading…" : "View signed agreement"}
      </button>
      {error && <p className="text-[11.5px] text-[var(--neg)]">{error}</p>}
    </div>
  );
}
