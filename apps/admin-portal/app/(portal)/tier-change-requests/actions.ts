"use server";

import { cookies } from "next/headers";
import { revalidatePath } from "next/cache";
import { ApiError, approveTierChangeRequest, rejectTierChangeRequest } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";

export interface TierChangeDecisionState {
  error?: string;
  decided?: boolean;
}

async function decide(
  id: string,
  reason: string,
  call: (baseUrl: string, accessToken: string, id: string, reason?: string) => Promise<unknown>,
): Promise<TierChangeDecisionState> {
  const jar = await cookies();
  const accessToken = jar.get("access_token")?.value;
  if (!accessToken) {
    return { error: "Your session has expired — please log in again." };
  }

  try {
    await call(coreAppBaseUrl(), accessToken, id, reason || undefined);
  } catch (error) {
    if (error instanceof ApiError && error.status === 403) {
      return { error: "You don't have permission to decide this request." };
    }
    if (error instanceof ApiError && error.status === 409) {
      return { error: "This request was already decided (in another tab, perhaps)." };
    }
    if (error instanceof ApiError && error.status === 404) {
      return { error: "No tier-change request exists with that ID." };
    }
    return { error: "Something went wrong — please try again." };
  }

  revalidatePath("/tier-change-requests");
  revalidatePath(`/tier-change-requests/${id}`);
  return { decided: true };
}

/** ADMIN+SUPER_ADMIN only — grants MASTER/FOLLOWER, enforced server-side by core-app itself. */
export async function approveTierChangeRequestAction(
  id: string,
  reason: string,
): Promise<TierChangeDecisionState> {
  return decide(id, reason, approveTierChangeRequest);
}

export async function rejectTierChangeRequestAction(
  id: string,
  reason: string,
): Promise<TierChangeDecisionState> {
  return decide(id, reason, rejectTierChangeRequest);
}
