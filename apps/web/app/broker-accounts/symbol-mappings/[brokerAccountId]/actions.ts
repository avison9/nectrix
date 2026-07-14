"use server";

import { confirmSymbolMapping } from "@nectrix/api-client";
import type { SymbolMappingEntry } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";

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
