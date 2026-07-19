"use server";

import { ApiError, createInvitation, revokeInvitation } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";

type ActionResult = { ok: true } | { error: string };

/** MASTER-only — a non-MASTER session's own real 403 from core-app is what actually gates this, not this form. */
export async function createInvitationAction(
  _prevState: ActionResult | null,
  formData: FormData,
): Promise<ActionResult> {
  const { accessToken } = await requireSession();
  const invitedEmail = String(formData.get("invitedEmail") ?? "");
  try {
    await createInvitation(coreAppBaseUrl(), accessToken, { invitedEmail });
    return { ok: true };
  } catch (error) {
    if (error instanceof ApiError) {
      const body = error.body as { error?: string } | null;
      if (body?.error === "master_profile_required") {
        return { error: "Set up your Master profile before inviting followers." };
      }
      return { error: "Couldn't send this invite — please try again." };
    }
    return { error: "Something went wrong — please try again." };
  }
}

export async function revokeInvitationAction(id: string): Promise<ActionResult> {
  const { accessToken } = await requireSession();
  try {
    await revokeInvitation(coreAppBaseUrl(), accessToken, id);
    return { ok: true };
  } catch (error) {
    if (error instanceof ApiError) {
      return { error: "Couldn't revoke this invite — please try again." };
    }
    return { error: "Something went wrong — please try again." };
  }
}
