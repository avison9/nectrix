"use server";

import { cookies } from "next/headers";
import { revalidatePath } from "next/cache";
import {
  ApiError,
  raiseDispute,
  resolveDispute,
  type FeeLedgerResolution,
} from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";

export interface DisputeActionState {
  error?: string;
  success?: string;
}

/** ADMIN+SUPPORT — the only real way performance_fee_ledger.status can ever become DISPUTED. */
export async function raiseDisputeAction(
  _prevState: DisputeActionState,
  formData: FormData,
): Promise<DisputeActionState> {
  const jar = await cookies();
  const accessToken = jar.get("access_token")?.value;
  if (!accessToken) {
    return { error: "Your session has expired — please log in again." };
  }

  const ledgerId = String(formData.get("ledgerId") ?? "").trim();
  const reason = String(formData.get("reason") ?? "").trim();
  if (!ledgerId || !reason) {
    return { error: "A ledger ID and reason are both required." };
  }

  try {
    await raiseDispute(coreAppBaseUrl(), accessToken, ledgerId, reason);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      return { error: "No fee ledger row exists with that ID." };
    }
    if (error instanceof ApiError && error.status === 403) {
      return { error: "You don't have permission to raise a dispute." };
    }
    return { error: "Failed to raise a dispute — check the ledger ID and try again." };
  }

  revalidatePath("/disputes");
  return { success: "Marked as disputed." };
}

export interface ResolveDisputeState {
  error?: string;
  resolved?: boolean;
}

/**
 * ADMIN-only server-side — matches the ticket's own RBAC line (financial-ledger action).
 * TICKET-117 bugfix — {@code resolved: true} on success lets the form show real confirmation and
 * disable itself instead of silently doing nothing from the caller's perspective (the prior
 * version returned {} either way, indistinguishable from "did this even run?" — see
 * FeeLedgerAdminApi#resolve's own 409 guard for the matching backend-side fix, since a caller
 * double-clicking before that confirmation lands used to insert a second, spurious resolution row).
 */
export async function resolveDisputeAction(
  ledgerId: string,
  input: { resolution: FeeLedgerResolution; note: string; adjustedAmount?: number },
): Promise<ResolveDisputeState> {
  const jar = await cookies();
  const accessToken = jar.get("access_token")?.value;
  if (!accessToken) {
    return { error: "Your session has expired — please log in again." };
  }

  try {
    await resolveDispute(coreAppBaseUrl(), accessToken, ledgerId, input);
  } catch (error) {
    if (error instanceof ApiError && error.status === 403) {
      return { error: "You don't have permission to resolve disputes." };
    }
    if (error instanceof ApiError && error.status === 409) {
      return { error: "This dispute was already resolved (in another tab, perhaps)." };
    }
    return { error: "Failed to resolve this dispute — please try again." };
  }

  revalidatePath("/disputes");
  revalidatePath(`/disputes/${ledgerId}`);
  return { resolved: true };
}
