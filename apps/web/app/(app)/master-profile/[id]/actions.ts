"use server";

import { ApiError, changeMasterPrimaryBrokerAccount, patchMasterProfile } from "@nectrix/api-client";
import type { MasterProfile, PrimaryBrokerAccountChange } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";

export async function patchMasterProfileAction(
  id: string,
  input: {
    displayName?: string;
    bio?: string;
    strategyTags?: string[];
    performanceFeePercent?: number;
    isPublic?: boolean;
  },
): Promise<MasterProfile | { error: string }> {
  const { accessToken } = await requireSession();
  try {
    return await patchMasterProfile(coreAppBaseUrl(), accessToken, id, input);
  } catch {
    return { error: "Could not save your changes — please try again." };
  }
}

/**
 * Bugfix — this was the missing capability: previously a Master's primary broker account could
 * only ever be set once, at profile creation, with no way to change it. See
 * bootstrap.archival.MasterPrimaryBrokerAccountOrchestrator's own Javadoc.
 */
export async function changePrimaryBrokerAccountAction(
  masterProfileId: string,
  brokerAccountId: string,
): Promise<PrimaryBrokerAccountChange | { error: string }> {
  const { accessToken } = await requireSession();
  try {
    return await changeMasterPrimaryBrokerAccount(
      coreAppBaseUrl(),
      accessToken,
      masterProfileId,
      brokerAccountId,
    );
  } catch (error) {
    if (error instanceof ApiError && error.status === 403) {
      return { error: "That broker account doesn't belong to you." };
    }
    return { error: "Could not change your primary broker account — please try again." };
  }
}
