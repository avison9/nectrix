"use server";

import { ApiError, createBrokerIbLink, deactivateBrokerIbLink } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";

type ActionResult = { ok: true } | { error: string };

/** MASTER-only — a non-MASTER session's own real 403 from core-app is what actually gates this. */
export async function createBrokerIbLinkAction(
  _prevState: ActionResult | null,
  formData: FormData,
): Promise<ActionResult> {
  const { accessToken } = await requireSession();
  const brokerType = String(formData.get("brokerType") ?? "");
  const brokerDisplayName = String(formData.get("brokerDisplayName") ?? "");
  const ibReferralUrlOrCode = String(formData.get("ibReferralUrlOrCode") ?? "");
  try {
    await createBrokerIbLink(coreAppBaseUrl(), accessToken, {
      brokerType,
      brokerDisplayName,
      ibReferralUrlOrCode,
    });
    return { ok: true };
  } catch (error) {
    if (error instanceof ApiError) {
      const body = error.body as { error?: string } | null;
      if (body?.error === "master_profile_required") {
        return { error: "Set up your Master profile before adding an IB link." };
      }
      return { error: "Couldn't add this IB link — please try again." };
    }
    return { error: "Something went wrong — please try again." };
  }
}

export async function deactivateBrokerIbLinkAction(id: string): Promise<ActionResult> {
  const { accessToken } = await requireSession();
  try {
    await deactivateBrokerIbLink(coreAppBaseUrl(), accessToken, id);
    return { ok: true };
  } catch (error) {
    if (error instanceof ApiError) {
      return { error: "Couldn't deactivate this IB link — please try again." };
    }
    return { error: "Something went wrong — please try again." };
  }
}
