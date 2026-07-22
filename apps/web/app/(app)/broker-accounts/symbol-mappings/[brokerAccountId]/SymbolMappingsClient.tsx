"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState, useTransition } from "react";
import type { SymbolMappingEntry } from "@nectrix/api-client";
import { SymbolMappingRow } from "./SymbolMappingRow";
import { ManualSymbolMappingForm } from "./ManualSymbolMappingForm";
import { confirmSymbolMappingAction, refetchSymbolMappingsAction } from "./actions";

const POLL_MS = 5000;

export function SymbolMappingsClient({
  brokerAccountId,
  initialMappings,
}: {
  brokerAccountId: string;
  initialMappings: SymbolMappingEntry[];
}) {
  const router = useRouter();
  const [mappings, setMappings] = useState(initialMappings);
  const [selectAllError, setSelectAllError] = useState<string | undefined>();
  const [selectAllPending, startSelectAllTransition] = useTransition();
  const allConfirmed = mappings.length > 0 && mappings.every((m) => m.isConfirmed);
  const unconfirmedCount = mappings.filter((m) => !m.isConfirmed).length;

  /**
   * Bugfix — every returned suggestion previously required its own individual Confirm click
   * before Continue would ever enable; for an account with many symbols that's a lot of
   * one-at-a-time clicking. Confirms each still-unconfirmed row's own already-suggested
   * brokerSymbolName in parallel (this is the PUT .../symbol-mappings/{canonicalSymbol} confirm
   * path, a plain DB update — not the manual-add /resolve path, which round-trips the broker to
   * validate a typed name, so there's no per-symbol broker call to worry about batching here). A
   * row the user has edited but not yet individually confirmed still uses ITS OWN typed value,
   * since that edit already lives in that row's own component state, not lifted up here.
   */
  function confirmAll() {
    setSelectAllError(undefined);
    const unconfirmed = mappings.filter((m) => !m.isConfirmed);
    startSelectAllTransition(async () => {
      const results = await Promise.all(
        unconfirmed.map((m) =>
          confirmSymbolMappingAction(brokerAccountId, m.canonicalSymbol, m.brokerSymbolName),
        ),
      );
      const failures = results.filter(
        (r): r is { error: string } => "error" in r,
      ).length;
      setMappings((prev) => {
        const byCanonicalSymbol = new Map(
          results
            .filter((r): r is SymbolMappingEntry => !("error" in r))
            .map((r) => [r.canonicalSymbol, r]),
        );
        return prev.map((m) => byCanonicalSymbol.get(m.canonicalSymbol) ?? m);
      });
      if (failures > 0) {
        setSelectAllError(
          `${failures} of ${unconfirmed.length} symbols couldn't be confirmed — try again, or confirm them individually.`,
        );
      }
    });
  }

  // apps/broker-adapters' reconcile loop only populates suggestions up to ~30s after linking —
  // poll while the list is still empty so this page notices without a manual reload. Stops
  // itself the moment real rows show up.
  useEffect(() => {
    if (mappings.length > 0) return;
    const interval = setInterval(async () => {
      const fresh = await refetchSymbolMappingsAction(brokerAccountId);
      if (fresh.length > 0) {
        setMappings(fresh);
      }
    }, POLL_MS);
    return () => clearInterval(interval);
  }, [brokerAccountId, mappings.length]);

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
          connection is established (usually within 30 seconds). This page checks
          automatically, or add one manually below.
        </p>
      ) : (
        <div className="mt-6 flex flex-col gap-2.5">
          <div className="flex items-center justify-between">
            <span className="text-[12.5px] text-[var(--text-2)]">
              {mappings.length} symbol{mappings.length === 1 ? "" : "s"} returned
            </span>
            {unconfirmedCount > 0 && (
              <button
                type="button"
                onClick={confirmAll}
                disabled={selectAllPending}
                className="h-8 rounded-[8px] border border-[var(--border)] px-3 text-[12.5px] font-semibold text-[var(--text)] transition-colors hover:bg-[var(--surface-2)] disabled:opacity-60"
              >
                {selectAllPending ? "Confirming…" : `Confirm all (${unconfirmedCount})`}
              </button>
            )}
          </div>
          {selectAllError && <p className="text-[12px] text-[var(--neg)]">{selectAllError}</p>}
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
