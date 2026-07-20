"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import type { CopyRelationshipStatus } from "@nectrix/domain-model";
import { pauseCopyRelationshipAction, resumeCopyRelationshipAction } from "./[id]/actions";

/**
 * Card-level equivalent of the detail page's own CopyRelationshipActions — deliberately just
 * Pause/Resume (the mock's own card only ever shows one action button beyond "Details"). The
 * risk-ack/agreement/stop states stay detail-page-only, reachable via "Details".
 */
export function CopyActionButtons({
  id,
  status,
}: {
  id: string;
  status: CopyRelationshipStatus;
}) {
  const router = useRouter();
  const [error, setError] = useState<string | undefined>();
  const [pending, startTransition] = useTransition();

  function run(action: (id: string) => Promise<unknown>) {
    setError(undefined);
    startTransition(async () => {
      const result = await action(id);
      if (result && typeof result === "object" && "error" in result) {
        setError((result as { error: string }).error);
        return;
      }
      router.refresh();
    });
  }

  if (status !== "ACTIVE" && status !== "PAUSED") {
    return null;
  }

  return (
    <div className="flex flex-col gap-1">
      <button
        type="button"
        disabled={pending}
        onClick={() =>
          run(status === "ACTIVE" ? pauseCopyRelationshipAction : resumeCopyRelationshipAction)
        }
        className={`h-[38px] flex-1 rounded-[9px] text-[12.5px] font-semibold transition-colors disabled:opacity-60 ${
          status === "ACTIVE"
            ? "border border-[var(--border)] text-[var(--text)] hover:bg-[var(--surface-2)]"
            : "bg-[var(--accent)] text-white hover:opacity-90"
        }`}
      >
        {pending ? "…" : status === "ACTIVE" ? "Pause copying" : "Resume copying"}
      </button>
      {error && <p className="text-[11px] text-[var(--neg)]">{error}</p>}
    </div>
  );
}
