"use client";

import { useState, useTransition } from "react";
import type { SymbolMappingEntry } from "@nectrix/api-client";
import { confirmSymbolMappingAction } from "./actions";

export function SymbolMappingRow({
  brokerAccountId,
  mapping,
  onConfirmed,
}: {
  brokerAccountId: string;
  mapping: SymbolMappingEntry;
  onConfirmed: (updated: SymbolMappingEntry) => void;
}) {
  const [brokerSymbolName, setBrokerSymbolName] = useState(mapping.brokerSymbolName);
  const [error, setError] = useState<string | undefined>();
  const [pending, startTransition] = useTransition();

  function confirm() {
    setError(undefined);
    startTransition(async () => {
      const result = await confirmSymbolMappingAction(
        brokerAccountId,
        mapping.canonicalSymbol,
        brokerSymbolName,
      );
      if ("error" in result) {
        setError(result.error);
        return;
      }
      onConfirmed(result);
    });
  }

  return (
    <div className="flex items-center gap-3 rounded-[10px] border border-[var(--border)] bg-[var(--surface)] p-3">
      <span className="w-24 text-[13.5px] font-medium text-[var(--text)]">
        {mapping.canonicalSymbol}
      </span>
      <input
        value={brokerSymbolName}
        onChange={(e) => setBrokerSymbolName(e.target.value)}
        disabled={mapping.isConfirmed}
        className="h-9 flex-1 rounded-[8px] border border-[var(--border)] bg-transparent px-2.5 text-[13px] text-[var(--text)] outline-none focus:border-[var(--accent)] disabled:opacity-60"
      />
      {mapping.isConfirmed ? (
        <span className="text-[12px] font-semibold text-[var(--pos)]">Confirmed</span>
      ) : (
        <button
          type="button"
          onClick={confirm}
          disabled={pending}
          className="h-9 rounded-[8px] bg-[var(--accent)] px-3 text-[12.5px] font-semibold text-white disabled:opacity-60"
        >
          {pending ? "Saving…" : "Confirm"}
        </button>
      )}
      {error && <span className="text-[12px] text-[var(--neg)]">{error}</span>}
    </div>
  );
}
