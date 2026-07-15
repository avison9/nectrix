"use server";

import { ApiError, createMasterProfile } from "@nectrix/api-client";
import type { CreateMasterProfileInput, MasterProfile } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";

export async function createMasterProfileAction(
  input: CreateMasterProfileInput,
): Promise<MasterProfile | { error: string; existingProfileId?: string }> {
  const { accessToken } = await requireSession();
  try {
    return await createMasterProfile(coreAppBaseUrl(), accessToken, input);
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.status === 409) {
        const body = error.body as { existing_profile_id?: string } | null;
        return {
          error: "You already have a Master profile.",
          existingProfileId: body?.existing_profile_id,
        };
      }
      if (error.status === 403) {
        return { error: "Only accounts with the Master role can create a Master profile." };
      }
    }
    return { error: "Could not create your Master profile — please try again." };
  }
}
