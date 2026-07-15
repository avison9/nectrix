"use server";

import { patchMasterProfile } from "@nectrix/api-client";
import type { MasterProfile } from "@nectrix/api-client";
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
