"use server";

import {
  ApiError,
  confirmSymbolMapping,
  createOrConfirmSymbolMapping,
  listSymbolMappings,
} from "@nectrix/api-client";
import type { SymbolMappingEntry } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";

/**
 * apps/broker-adapters only populates suggestions once its own reconcile loop ticks (up to ~30s
 * after linking, see reconcile.go's own reconcileInterval) — this page had no way to notice once
 * that happened without a manual reload. Polled from the client while the list is still empty.
 */
export async function refetchSymbolMappingsAction(
  brokerAccountId: string,
): Promise<SymbolMappingEntry[]> {
  const { accessToken } = await requireSession();
  return listSymbolMappings(coreAppBaseUrl(), accessToken, brokerAccountId);
}

export async function confirmSymbolMappingAction(
  brokerAccountId: string,
  canonicalSymbol: string,
  brokerSymbolName: string,
): Promise<SymbolMappingEntry | { error: string }> {
  const { accessToken } = await requireSession();
  try {
    return await confirmSymbolMapping(
      coreAppBaseUrl(),
      accessToken,
      brokerAccountId,
      canonicalSymbol,
      brokerSymbolName,
    );
  } catch {
    return { error: "Could not confirm this mapping — please try again." };
  }
}

/** TICKET-116 — the manual fallback for a canonical symbol never auto-suggested. */
export async function createOrConfirmSymbolMappingAction(
  brokerAccountId: string,
  canonicalSymbol: string,
  brokerSymbolName: string,
): Promise<SymbolMappingEntry | { error: string }> {
  const { accessToken } = await requireSession();
  try {
    return await createOrConfirmSymbolMapping(
      coreAppBaseUrl(),
      accessToken,
      brokerAccountId,
      canonicalSymbol,
      brokerSymbolName,
    );
  } catch (error) {
    if (error instanceof ApiError && error.status === 422) {
      return {
        error: `Your broker doesn't recognize "${brokerSymbolName}" — check the exact symbol name shown in your trading platform.`,
      };
    }
    return { error: "Could not add this mapping — please try again." };
  }
}
