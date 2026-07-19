"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { raiseDisputeAction } from "./actions";

export function RaiseDisputeButton({ ledgerId }: { ledgerId: string }) {
  const router = useRouter();
  const [error, setError] = useState<string | undefined>();
  const [raised, setRaised] = useState(false);
  const [pending, startTransition] = useTransition();

  function onClick() {
    if (
      !window.confirm(
        "Raise a dispute on this settlement? An admin will review the computation before it's resolved.",
      )
    ) {
      return;
    }
    setError(undefined);
    startTransition(async () => {
      const result = await raiseDisputeAction(ledgerId);
      if (result.error) {
        setError(result.error);
        return;
      }
      setRaised(true);
      router.refresh();
    });
  }

  if (raised) {
    return <p className="text-[12.5px] font-medium text-[var(--pos)]">Dispute raised.</p>;
  }

  return (
    <div className="flex flex-col items-start gap-1">
      <button
        type="button"
        disabled={pending}
        onClick={onClick}
        className="h-9 rounded-[9px] border border-[var(--border)] px-3 text-[12.5px] font-semibold text-[var(--neg)] transition-colors hover:bg-[var(--neg)]/8 disabled:opacity-60"
      >
        {pending ? "Raising…" : "Raise a dispute"}
      </button>
      {error && <p className="text-[11.5px] text-[var(--neg)]">{error}</p>}
    </div>
  );
}
