"use server";

import { revalidatePath } from "next/cache";
import { ApiError, raiseMySettlementDispute } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";

export interface RaiseDisputeState {
  error?: string;
}

/**
 * TICKET-117 follow-up — either party (Master or Follower) can raise a dispute on their own
 * settlement row; core-app enforces ownership (FeeLedgerService) and rejects an already-disputed/
 * voided row with a real 409, not just a UI-hidden button. Same shape as
 * follower/commission/actions.ts's own copy — route-scoped duplication, not a cross-route import,
 * matching this app's established per-route actions.ts convention.
 */
export async function raiseDisputeAction(ledgerId: string): Promise<RaiseDisputeState> {
  const { accessToken } = await requireSession();
  try {
    await raiseMySettlementDispute(coreAppBaseUrl(), accessToken, ledgerId);
  } catch (error) {
    if (error instanceof ApiError && error.status === 409) {
      return { error: "This settlement has already been disputed or voided." };
    }
    return { error: "Failed to raise a dispute — please try again." };
  }
  revalidatePath("/commission");
  revalidatePath(`/commission/${ledgerId}`);
  return {};
}
