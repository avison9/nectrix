"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import type { SymbolMappingEntry } from "@nectrix/api-client";
import { SymbolMappingRow } from "./SymbolMappingRow";
import { ManualSymbolMappingForm } from "./ManualSymbolMappingForm";

export function SymbolMappingsClient({
  brokerAccountId,
  initialMappings,
}: {
  brokerAccountId: string;
  initialMappings: SymbolMappingEntry[];
}) {
  const router = useRouter();
  const [mappings, setMappings] = useState(initialMappings);
  const allConfirmed = mappings.length > 0 && mappings.every((m) => m.isConfirmed);

  function handleConfirmed(updated: SymbolMappingEntry) {
    setMappings((prev) => prev.map((m) => (m.id === updated.id ? updated : m)));
  }

  function handleManuallyAdded(added: SymbolMappingEntry) {
    setMappings((prev) => {
      const exists = prev.some((m) => m.canonicalSymbol === added.canonicalSymbol);
      return exists
        ? prev.map((m) => (m.canonicalSymbol === added.canonicalSymbol ? added : m))
        : [...prev, added];
    });
  }

  return (
    <>
      {mappings.length === 0 ? (
        <p className="mt-6 text-[13px] text-[var(--text-2)]">
          No symbol suggestions yet — they&apos;re populated automatically once the broker
          connection is established. Check back shortly, or add one manually below.
        </p>
      ) : (
        <div className="mt-6 flex flex-col gap-2.5">
          {mappings.map((mapping) => (
            <SymbolMappingRow
              key={mapping.id}
              brokerAccountId={brokerAccountId}
              mapping={mapping}
              onConfirmed={handleConfirmed}
            />
          ))}
        </div>
      )}

      <ManualSymbolMappingForm brokerAccountId={brokerAccountId} onAdded={handleManuallyAdded} />

      <button
        type="button"
        disabled={!allConfirmed}
        onClick={() => router.push(`/broker-accounts/role/${brokerAccountId}`)}
        className="mt-6 h-[42px] rounded-[11px] bg-[var(--accent)] text-[13.5px] font-semibold text-white transition-opacity disabled:opacity-40"
      >
        Continue
      </button>
    </>
  );
}
