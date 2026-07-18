"use client";

import { useState, useTransition } from "react";
import type { SymbolMappingEntry } from "@nectrix/api-client";
import { createOrConfirmSymbolMappingAction } from "./actions";

/**
 * TICKET-116 — the manual fallback: TICKET-103's auto-suggestion probe list (a fixed set of common
 * broker symbol-naming conventions) doesn't cover every real broker, so a canonical symbol can be
 * left with no row at all. This form lets the user type the exact name their own broker uses;
 * `createOrConfirmSymbolMappingAction` verifies it against a live broker round trip server-side
 * before writing anything — a 422 here means the broker genuinely doesn't recognize the name typed.
 */
export function ManualSymbolMappingForm({
  brokerAccountId,
  onAdded,
}: {
  brokerAccountId: string;
  onAdded: (mapping: SymbolMappingEntry) => void;
}) {
  const [canonicalSymbol, setCanonicalSymbol] = useState("");
  const [brokerSymbolName, setBrokerSymbolName] = useState("");
  const [error, setError] = useState<string | undefined>();
  const [pending, startTransition] = useTransition();

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(undefined);
    startTransition(async () => {
      const result = await createOrConfirmSymbolMappingAction(
        brokerAccountId,
        canonicalSymbol.trim().toUpperCase(),
        brokerSymbolName.trim(),
      );
      if ("error" in result) {
        setError(result.error);
        return;
      }
      onAdded(result);
      setCanonicalSymbol("");
      setBrokerSymbolName("");
    });
  }

  return (
    <form
      onSubmit={submit}
      className="mt-3 flex flex-col gap-3 rounded-[10px] border border-dashed border-[var(--border)] p-3.5"
    >
      <p className="text-[12.5px] text-[var(--text-2)]">
        Don&apos;t see a symbol you need? Add it manually — we&apos;ll verify the name against your
        broker before saving.
      </p>
      <div className="flex flex-wrap items-center gap-2.5">
        <input
          value={canonicalSymbol}
          onChange={(e) => setCanonicalSymbol(e.target.value)}
          placeholder="Canonical symbol (e.g. EURUSD)"
          required
          className="h-9 w-[190px] rounded-[8px] border border-[var(--border)] bg-transparent px-2.5 font-mono text-[13px] uppercase text-[var(--text)] outline-none focus:border-[var(--accent)]"
        />
        <input
          value={brokerSymbolName}
          onChange={(e) => setBrokerSymbolName(e.target.value)}
          placeholder="Your broker's symbol name (e.g. EURUSD.a)"
          required
          className="h-9 flex-1 min-w-[200px] rounded-[8px] border border-[var(--border)] bg-transparent px-2.5 text-[13px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
        />
        <button
          type="submit"
          disabled={pending}
          className="h-9 rounded-[8px] bg-[var(--accent)] px-3.5 text-[12.5px] font-semibold text-white disabled:opacity-60"
        >
          {pending ? "Verifying…" : "Add"}
        </button>
      </div>
      {error && <span className="text-[12px] text-[var(--neg)]">{error}</span>}
    </form>
  );
}
