"use server";

import { redirect } from "next/navigation";
import { ApiError, createCopyRelationshipFromInvitation } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";

export interface StartCopyingActionState {
  error?: string;
}

export async function startCopyingAction(
  _prevState: StartCopyingActionState,
  formData: FormData,
): Promise<StartCopyingActionState> {
  const { accessToken } = await requireSession();
  const invitationId = String(formData.get("invitationId") ?? "");
  const followerBrokerAccountId = String(formData.get("followerBrokerAccountId") ?? "");
  const customize = formData.get("customize") === "on";

  try {
    await createCopyRelationshipFromInvitation(coreAppBaseUrl(), accessToken, {
      invitationId,
      followerBrokerAccountId,
      ...(customize
        ? {
            multiplier: numberOrUndefined(formData.get("multiplier")),
            maxLotPerTrade: numberOrUndefined(formData.get("maxLotPerTrade")),
            maxOpenPositions: numberOrUndefined(formData.get("maxOpenPositions")),
          }
        : {}),
    });
  } catch (error) {
    if (error instanceof ApiError) {
      const body = error.body as { error?: string } | null;
      if (body?.error === "invitation_already_used") {
        // Already actioned (e.g. a duplicate submit) — treat as success, not an error.
        redirect("/onboarding");
      }
      if (body?.error === "insufficient_follower_balance") {
        return {
          error:
            "Your account balance doesn't meet this Master's minimum requirement to start copying.",
        };
      }
      return { error: "Couldn't start copying — please try again." };
    }
    return { error: "Something went wrong — please try again." };
  }

  redirect("/onboarding");
}

function numberOrUndefined(value: FormDataEntryValue | null): number | undefined {
  if (value === null || value === "") return undefined;
  const n = Number(value);
  return Number.isFinite(n) ? n : undefined;
}
