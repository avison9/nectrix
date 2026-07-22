"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import type { CopyRelationship } from "@nectrix/api-client";
import {
  acknowledgeRiskAction,
  pauseCopyRelationshipAction,
  resumeCopyRelationshipAction,
  signAgreementAction,
  stopCopyRelationshipAction,
} from "./actions";
import { ViewAgreementLink } from "./ViewAgreementLink";

/**
 * AC2/AC3 — these buttons are only ever a convenience: core-app itself
 * rejects any transition invalid for the relationship's current status
 * (see CopyRelationshipService's requireStatus), so hiding the wrong button
 * here is a UX nicety, not the actual gate.
 */
export function CopyRelationshipActions({ relationship }: { relationship: CopyRelationship }) {
  const router = useRouter();
  const [error, setError] = useState<string | undefined>();
  const [pending, startTransition] = useTransition();

  function run(action: (id: string) => Promise<CopyRelationship | { error: string }>) {
    setError(undefined);
    startTransition(async () => {
      const result = await action(relationship.id);
      if ("error" in result) {
        setError(result.error);
        return;
      }
      router.refresh();
    });
  }

  return (
    <div className="mt-6 flex flex-col gap-3">
      {relationship.status === "PENDING_RISK_ACK" && (
        <div className="rounded-[12px] border border-amber-500/30 bg-amber-500/10 p-4">
          <p className="text-[13.5px] font-medium text-[var(--text)]">
            Acknowledge the risks of copy trading before this relationship can go live.
          </p>
          <button
            type="button"
            disabled={pending}
            onClick={() => run(acknowledgeRiskAction)}
            className="mt-3 h-10 rounded-[10px] bg-[var(--accent)] px-4 text-[13px] font-semibold text-white disabled:opacity-60"
          >
            {pending ? "Submitting…" : "I understand the risks"}
          </button>
        </div>
      )}

      {relationship.status === "PENDING_AGREEMENT" && (
        <div className="rounded-[12px] border border-amber-500/30 bg-amber-500/10 p-4">
          <p className="text-[13.5px] font-medium text-[var(--text)]">
            Sign the management agreement to activate copying.
          </p>
          <button
            type="button"
            disabled={pending}
            onClick={() => run(signAgreementAction)}
            className="mt-3 h-10 rounded-[10px] bg-[var(--accent)] px-4 text-[13px] font-semibold text-white disabled:opacity-60"
          >
            {pending ? "Submitting…" : "Sign agreement"}
          </button>
        </div>
      )}

      {(relationship.status === "ACTIVE" || relationship.status === "PAUSED") && (
        <div className="flex gap-2.5">
          {relationship.status === "ACTIVE" && (
            <button
              type="button"
              disabled={pending}
              onClick={() => run(pauseCopyRelationshipAction)}
              className="h-10 flex-1 rounded-[10px] border border-[var(--border)] text-[13px] font-semibold text-[var(--text)] disabled:opacity-60"
            >
              Pause
            </button>
          )}
          {relationship.status === "PAUSED" && (
            <button
              type="button"
              disabled={pending}
              onClick={() => run(resumeCopyRelationshipAction)}
              className="h-10 flex-1 rounded-[10px] bg-[var(--accent)] text-[13px] font-semibold text-white disabled:opacity-60"
            >
              Resume
            </button>
          )}
          <button
            type="button"
            disabled={pending}
            onClick={() => run(stopCopyRelationshipAction)}
            className="h-10 flex-1 rounded-[10px] border border-[var(--neg)] text-[13px] font-semibold text-[var(--neg)] disabled:opacity-60"
          >
            Stop
          </button>
        </div>
      )}

      {relationship.status === "STOPPED" && (
        <p className="text-[13px] text-[var(--text-2)]">
          This relationship is stopped. Any open positions are force-closed automatically.
        </p>
      )}

      {relationship.feeCollectionMethod === "BROKER_PARTNERSHIP" &&
        relationship.status !== "PENDING_RISK_ACK" &&
        relationship.status !== "PENDING_AGREEMENT" && <ViewAgreementLink id={relationship.id} />}

      {error && <p className="text-[12.5px] text-[var(--neg)]">{error}</p>}
    </div>
  );
}
