"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";

const POLL_MS = 12000;

/**
 * TICKET-124 — polls router.refresh() (same mechanism RefreshButton.tsx uses manually) so an open
 * position's unrealized P&L keeps updating without the user having to click Refresh, same
 * "poll only while it matters, clearInterval on unmount" shape
 * SymbolMappingsClient.tsx's own polling already established. Only runs when the current page
 * actually has an OPEN/PARTIALLY_CLOSED row — nothing to keep live otherwise.
 */
export function LiveRefresh({ hasOpenTrades }: { hasOpenTrades: boolean }) {
  const router = useRouter();

  useEffect(() => {
    if (!hasOpenTrades) return;
    const interval = setInterval(() => router.refresh(), POLL_MS);
    return () => clearInterval(interval);
  }, [hasOpenTrades, router]);

  return null;
}
