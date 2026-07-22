"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import type { CopyRelationship } from "@nectrix/api-client";
import { updateCopySettingsAction } from "./actions";

const METHODS = [
  "FIXED_LOT",
  "PROPORTIONAL_EQUITY",
  "PROPORTIONAL_BALANCE",
  "RISK_PERCENT",
  "MULTIPLIER",
  "CUSTOM_FORMULA",
];
const ROUNDING_MODES = ["DOWN", "NEAREST", "UP"];

function toInputValue(n: number | null): string {
  return n === null ? "" : String(n);
}

function toNumberOrNull(s: string): number | null {
  return s.trim() === "" ? null : Number(s);
}

/**
 * TICKET-116 — the money-management/risk profile editing form the detail page's own comment
 * flagged as blocked ("editing requires ... an API that hasn't shipped yet"). Edits the existing
 * profile rows in place (a new `PATCH .../copy-settings` endpoint, distinct from the existing
 * `PATCH /copy-relationships/{id}` which only swaps to a *different* pre-existing profile id) — a
 * full-object submit, always pre-filled with the relationship's current values.
 *
 * <p>Drawdown pause/close-all percentages aren't editable here — the backing
 * `RiskProfileRepository#update` deliberately never touches those two columns (TICKET-108's own
 * scope), so they stay a read-only display, same as before.
 */
export function CopySettingsForm({ relationship }: { relationship: CopyRelationship }) {
  const router = useRouter();
  const [editing, setEditing] = useState(false);
  const [error, setError] = useState<string | undefined>();
  const [pending, startTransition] = useTransition();

  const [method, setMethod] = useState(relationship.moneyManagementProfile.method);
  const [fixedLotSize, setFixedLotSize] = useState(
    toInputValue(relationship.moneyManagementProfile.fixedLotSize),
  );
  const [multiplier, setMultiplier] = useState(
    toInputValue(relationship.moneyManagementProfile.multiplier),
  );
  const [riskPercent, setRiskPercent] = useState(
    toInputValue(relationship.moneyManagementProfile.riskPercent),
  );
  const [roundingMode, setRoundingMode] = useState(relationship.moneyManagementProfile.roundingMode);
  const [maxLotPerTrade, setMaxLotPerTrade] = useState(
    toInputValue(relationship.riskProfile.maxLotPerTrade),
  );
  const [maxOpenPositions, setMaxOpenPositions] = useState(
    toInputValue(relationship.riskProfile.maxOpenPositions),
  );
  const [maxSlippagePips, setMaxSlippagePips] = useState(String(relationship.riskProfile.maxSlippagePips));
  const [excludedSymbols, setExcludedSymbols] = useState(relationship.excludedSymbols);
  const [newSymbol, setNewSymbol] = useState("");

  function addExcludedSymbol() {
    const symbol = newSymbol.trim().toUpperCase();
    if (!symbol || excludedSymbols.includes(symbol)) {
      setNewSymbol("");
      return;
    }
    setExcludedSymbols((prev) => [...prev, symbol]);
    setNewSymbol("");
  }

  function removeExcludedSymbol(symbol: string) {
    setExcludedSymbols((prev) => prev.filter((s) => s !== symbol));
  }

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(undefined);
    startTransition(async () => {
      const result = await updateCopySettingsAction(relationship.id, {
        method,
        fixedLotSize: toNumberOrNull(fixedLotSize),
        multiplier: toNumberOrNull(multiplier),
        riskPercent: toNumberOrNull(riskPercent),
        roundingMode,
        maxLotPerTrade: toNumberOrNull(maxLotPerTrade),
        maxOpenPositions: toNumberOrNull(maxOpenPositions),
        maxSlippagePips: toNumberOrNull(maxSlippagePips),
        excludedSymbols,
      });
      if ("error" in result) {
        setError(result.error);
        return;
      }
      setEditing(false);
      router.refresh();
    });
  }

  if (!editing) {
    return (
      <button
        type="button"
        onClick={() => setEditing(true)}
        className="mt-3 text-[12.5px] font-medium text-[var(--accent)] underline-offset-2 hover:underline"
      >
        Edit copy settings
      </button>
    );
  }

  return (
    <form
      onSubmit={submit}
      className="mt-3 flex flex-col gap-3 rounded-[12px] border border-[var(--border)] bg-[var(--surface)] p-4"
    >
      <div className="grid grid-cols-2 gap-3">
        <label className="flex flex-col gap-1.5">
          <span className="text-[11.5px] font-medium text-[var(--text-2)]">Copy mode</span>
          <select
            value={method}
            onChange={(e) => setMethod(e.target.value)}
            className="h-9 rounded-[8px] border border-[var(--border)] bg-transparent px-2.5 text-[12.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          >
            {METHODS.map((m) => (
              <option key={m} value={m}>
                {m}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1.5">
          <span className="text-[11.5px] font-medium text-[var(--text-2)]">Rounding mode</span>
          <select
            value={roundingMode}
            onChange={(e) => setRoundingMode(e.target.value)}
            className="h-9 rounded-[8px] border border-[var(--border)] bg-transparent px-2.5 text-[12.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          >
            {ROUNDING_MODES.map((m) => (
              <option key={m} value={m}>
                {m}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1.5">
          <span className="text-[11.5px] font-medium text-[var(--text-2)]">Fixed lot size</span>
          <input
            type="number"
            step="0.01"
            value={fixedLotSize}
            onChange={(e) => setFixedLotSize(e.target.value)}
            className="h-9 rounded-[8px] border border-[var(--border)] bg-transparent px-2.5 font-mono text-[12.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          />
        </label>
        <label className="flex flex-col gap-1.5">
          <span className="text-[11.5px] font-medium text-[var(--text-2)]">Lot multiplier</span>
          <input
            type="number"
            step="0.01"
            value={multiplier}
            onChange={(e) => setMultiplier(e.target.value)}
            className="h-9 rounded-[8px] border border-[var(--border)] bg-transparent px-2.5 font-mono text-[12.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          />
        </label>
        <label className="flex flex-col gap-1.5">
          <span className="text-[11.5px] font-medium text-[var(--text-2)]">Risk percent</span>
          <input
            type="number"
            step="0.01"
            value={riskPercent}
            onChange={(e) => setRiskPercent(e.target.value)}
            className="h-9 rounded-[8px] border border-[var(--border)] bg-transparent px-2.5 font-mono text-[12.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          />
        </label>
        <label className="flex flex-col gap-1.5">
          <span className="text-[11.5px] font-medium text-[var(--text-2)]">Max lot per trade</span>
          <input
            type="number"
            step="0.01"
            value={maxLotPerTrade}
            onChange={(e) => setMaxLotPerTrade(e.target.value)}
            className="h-9 rounded-[8px] border border-[var(--border)] bg-transparent px-2.5 font-mono text-[12.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          />
        </label>
        <label className="flex flex-col gap-1.5">
          <span className="text-[11.5px] font-medium text-[var(--text-2)]">Max open positions</span>
          <input
            type="number"
            step="1"
            value={maxOpenPositions}
            onChange={(e) => setMaxOpenPositions(e.target.value)}
            className="h-9 rounded-[8px] border border-[var(--border)] bg-transparent px-2.5 font-mono text-[12.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          />
        </label>
        <label className="flex flex-col gap-1.5">
          <span className="text-[11.5px] font-medium text-[var(--text-2)]">Max slippage (pips)</span>
          <input
            type="number"
            step="0.1"
            value={maxSlippagePips}
            onChange={(e) => setMaxSlippagePips(e.target.value)}
            className="h-9 rounded-[8px] border border-[var(--border)] bg-transparent px-2.5 font-mono text-[12.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          />
        </label>
      </div>

      <div className="flex flex-col gap-1.5">
        <span className="text-[11.5px] font-medium text-[var(--text-2)]">
          Excluded symbols — never copied from this Master
        </span>
        <div className="flex flex-wrap gap-1.5">
          {excludedSymbols.length === 0 && (
            <span className="text-[12px] text-[var(--text-3)]">
              None — every symbol this Master trades is copied.
            </span>
          )}
          {excludedSymbols.map((symbol) => (
            <span
              key={symbol}
              className="flex items-center gap-1.5 rounded-full bg-[var(--surface-2)] py-1 pl-2.5 pr-1.5 text-[12px] font-mono font-semibold text-[var(--text)]"
            >
              {symbol}
              <button
                type="button"
                onClick={() => removeExcludedSymbol(symbol)}
                aria-label={`Remove ${symbol}`}
                className="flex h-4 w-4 items-center justify-center rounded-full text-[var(--text-2)] hover:bg-[var(--border)] hover:text-[var(--text)]"
              >
                ×
              </button>
            </span>
          ))}
        </div>
        <div className="flex gap-2">
          <input
            value={newSymbol}
            onChange={(e) => setNewSymbol(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                addExcludedSymbol();
              }
            }}
            placeholder="e.g. EURUSD"
            className="h-9 w-32 rounded-[8px] border border-[var(--border)] bg-transparent px-2.5 font-mono text-[12.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          />
          <button
            type="button"
            onClick={addExcludedSymbol}
            className="h-9 rounded-[8px] border border-[var(--border)] px-3 text-[12.5px] font-semibold text-[var(--text)] hover:bg-[var(--surface-2)]"
          >
            Add
          </button>
        </div>
      </div>

      {error && <p className="text-[12.5px] text-[var(--neg)]">{error}</p>}

      <div className="flex gap-2.5">
        <button
          type="submit"
          disabled={pending}
          className="h-9 rounded-[9px] bg-[var(--accent)] px-4 text-[12.5px] font-semibold text-white disabled:opacity-60"
        >
          {pending ? "Saving…" : "Save changes"}
        </button>
        <button
          type="button"
          onClick={() => setEditing(false)}
          disabled={pending}
          className="h-9 rounded-[9px] border border-[var(--border)] px-4 text-[12.5px] font-semibold text-[var(--text)] disabled:opacity-60"
        >
          Cancel
        </button>
      </div>
    </form>
  );
}
