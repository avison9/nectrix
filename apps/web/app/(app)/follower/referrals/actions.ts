"use server";

import { ApiError, nominateProspect } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";

type ActionResult = { ok: true } | { error: string };

/** FOLLOWER-only — a non-Follower session's own real 403 from core-app is what actually gates this. */
export async function nominateProspectAction(
  _prevState: ActionResult | null,
  formData: FormData,
): Promise<ActionResult> {
  const { accessToken } = await requireSession();
  const prospectEmail = String(formData.get("prospectEmail") ?? "");
  try {
    await nominateProspect(coreAppBaseUrl(), accessToken, prospectEmail);
    return { ok: true };
  } catch (error) {
    if (error instanceof ApiError) {
      const body = error.body as { error?: string } | null;
      if (body?.error === "no_master_to_nominate_to") {
        return { error: "You don't have a master to refer this trader to yet." };
      }
      return { error: "Couldn't send this referral — please try again." };
    }
    return { error: "Something went wrong — please try again." };
  }
}
