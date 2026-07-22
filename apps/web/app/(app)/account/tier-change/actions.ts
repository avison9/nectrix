"use server";

import { ApiError, submitTierChangeRequest } from "@nectrix/api-client";
import type { TierChangeTargetRole } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";

type ActionResult = { ok: true } | { error: string };

/**
 * TICKET-122. Individual-mode-only — a caller who already holds MASTER/FOLLOWER gets a real 409
 * from core-app (`already_master_or_follower`), same as a second submission while one is still
 * PENDING (`pending_request_exists`) or an unchecked agreement checkbox (`agreement_not_accepted`,
 * enforced server-side, not just by the checkbox's own `required` attribute).
 */
export async function submitTierChangeRequestAction(
  _prevState: ActionResult | null,
  formData: FormData,
): Promise<ActionResult> {
  const { accessToken } = await requireSession();
  const targetMode = String(formData.get("targetMode") ?? "") as TierChangeTargetRole;
  const agreementAccepted = formData.get("agreementAccepted") === "on";
  try {
    await submitTierChangeRequest(coreAppBaseUrl(), accessToken, { targetMode, agreementAccepted });
    return { ok: true };
  } catch (error) {
    if (error instanceof ApiError) {
      const body = error.body as { error?: string } | null;
      if (body?.error === "already_master_or_follower") {
        return { error: "You already have Master or Follower access." };
      }
      if (body?.error === "pending_request_exists") {
        return { error: "You already have a pending tier-change request." };
      }
      if (body?.error === "agreement_not_accepted") {
        return { error: "You must accept the agreement to submit this request." };
      }
      return { error: "Couldn't submit this request — please try again." };
    }
    return { error: "Something went wrong — please try again." };
  }
}
