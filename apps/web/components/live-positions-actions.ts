"use server";

import { listAllCopiedTrades } from "@nectrix/api-client";
import type { CopiedTrade } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";

const OPEN_STATUSES = new Set(["FILLED", "PARTIALLY_CLOSED"]);

/**
 * TICKET-124 — LivePositionsFeed's own refetch: reuses the exact same enriched (unrealizedPnl
 * included) data Trade History already fetches via listAllCopiedTrades, filtered down to
 * currently-open rows. One shared computation path for both surfaces, not a second one.
 */
export async function refetchOpenPositionsAction(role: "follower" | "master"): Promise<CopiedTrade[]> {
  const { accessToken } = await requireSession();
  const page = await listAllCopiedTrades(coreAppBaseUrl(), accessToken, {
    role,
    pageSize: 50,
  });
  return page.trades.filter((t) => OPEN_STATUSES.has(t.status));
}
