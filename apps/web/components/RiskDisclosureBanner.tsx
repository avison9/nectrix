"use client";

import { useSyncExternalStore } from "react";

const STORAGE_KEY = "nectrix_risk_disclosure_ack";

// localStorage writes from this same tab don't fire a "storage" event (only other tabs get
// that) -- this tiny listener set is how acknowledge() below tells useSyncExternalStore to
// re-read getSnapshot() right after writing.
const listeners = new Set<() => void>();

function subscribe(onStoreChange: () => void): () => void {
  listeners.add(onStoreChange);
  return () => listeners.delete(onStoreChange);
}

function getSnapshot(): boolean {
  return localStorage.getItem(STORAGE_KEY) === "true";
}

function acknowledge(): void {
  localStorage.setItem(STORAGE_KEY, "true");
  for (const listener of listeners) listener();
}

// Server has no localStorage -- assume acknowledged so SSR never renders the overlay; the client
// re-syncs to the real value on mount via useSyncExternalStore, no hydration-mismatch warning.
function getServerSnapshot(): boolean {
  return true;
}

/**
 * TICKET-112 AC4 — "risk disclosure... requires acknowledgment before any first follow action is
 * possible from a discovery page" (docs/10-portfolio-social-trading.md §10.2's compliance flag).
 * No mock section exists for this — the mock has no discovery-page risk banner at all — so this
 * is designed from scratch, consistent with the established visual system (card radii, type
 * scale, grey/stone `--accent`), not copied from a section that doesn't exist.
 *
 * No backend "has this anonymous visitor seen this" tracking exists (there's no user account
 * yet for a first-time visitor), so this is a client-side, per-browser gate via localStorage —
 * a full-page blocking overlay, not a dismissible toast; only the explicit "I understand" button
 * clears it, never an ×/click-outside.
 */
export function RiskDisclosureBanner() {
  const acknowledged = useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot);

  if (acknowledged) {
    return null;
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <div className="w-full max-w-[440px] rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-6">
        <h2 className="text-[17px] font-semibold tracking-tight text-[var(--text)]">
          Before you browse
        </h2>
        <p className="mt-2.5 text-[13.5px] leading-[1.6] text-[var(--text-2)]">
          Past performance is not indicative of future results. Copy trading carries a genuine
          risk of loss, including loss of your full deposited capital — never risk money you
          can&apos;t afford to lose. Metrics shown here are computed from actual copied-trade data
          on this platform, not self-reported, but they are historical, not a forward-looking
          promise.
        </p>
        <button
          type="button"
          onClick={acknowledge}
          className="mt-5 h-11 w-full rounded-[11px] bg-[var(--accent)] text-sm font-semibold text-white transition-opacity hover:opacity-90"
        >
          I understand
        </button>
      </div>
    </div>
  );
}
