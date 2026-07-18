"use client";

import { useRouter } from "next/navigation";
import { useTransition } from "react";
import type { CopyRelationship } from "@nectrix/api-client";
import {
  pauseCopyRelationshipAction,
  resumeCopyRelationshipAction,
  stopCopyRelationshipAction,
} from "@/app/(app)/copy-relationships/[id]/actions";

/**
 * TICKET-116 — the dashboard's compact pause/resume/stop row, reusing the exact same server
 * actions the copy-relationship detail page's own CopyRelationshipActions calls (no duplicated
 * mutation logic) — just a tighter layout for a table/card row instead of a full detail panel.
 */
export function CompactRelationshipActions({ relationship }: { relationship: CopyRelationship }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();

  function run(action: (id: string) => Promise<CopyRelationship | { error: string }>) {
    startTransition(async () => {
      await action(relationship.id);
      router.refresh();
    });
  }

  if (relationship.status !== "ACTIVE" && relationship.status !== "PAUSED") {
    return null;
  }

  return (
    <div className="flex gap-1.5">
      {relationship.status === "ACTIVE" && (
        <button
          type="button"
          disabled={pending}
          onClick={() => run(pauseCopyRelationshipAction)}
          className="h-7 rounded-[7px] border border-[var(--border)] px-2.5 text-[11.5px] font-semibold text-[var(--text)] disabled:opacity-60"
        >
          Pause
        </button>
      )}
      {relationship.status === "PAUSED" && (
        <button
          type="button"
          disabled={pending}
          onClick={() => run(resumeCopyRelationshipAction)}
          className="h-7 rounded-[7px] bg-[var(--accent)] px-2.5 text-[11.5px] font-semibold text-white disabled:opacity-60"
        >
          Resume
        </button>
      )}
      <button
        type="button"
        disabled={pending}
        onClick={() => run(stopCopyRelationshipAction)}
        className="h-7 rounded-[7px] border border-[var(--neg)] px-2.5 text-[11.5px] font-semibold text-[var(--neg)] disabled:opacity-60"
      >
        Stop
      </button>
    </div>
  );
}
