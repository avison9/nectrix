"use server";

import { ApiError, createInvitation, dismissNomination, markNominationInvited } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";

type ActionResult = { ok: true } | { error: string };

/**
 * MASTER-only — creates the real TICKET-118 invitation (same endpoint `/master/followers` uses)
 * targeted at the nominated prospect's email, then records that this nomination was actioned.
 */
export async function sendInviteForNominationAction(
  nominationId: string,
  prospectEmail: string,
): Promise<ActionResult> {
  const { accessToken } = await requireSession();
  try {
    const invitation = await createInvitation(coreAppBaseUrl(), accessToken, {
      invitedEmail: prospectEmail,
    });
    await markNominationInvited(coreAppBaseUrl(), accessToken, nominationId, invitation.id);
    return { ok: true };
  } catch (error) {
    if (error instanceof ApiError) {
      return { error: "Couldn't send this invite — please try again." };
    }
    return { error: "Something went wrong — please try again." };
  }
}

export async function dismissNominationAction(nominationId: string): Promise<ActionResult> {
  const { accessToken } = await requireSession();
  try {
    await dismissNomination(coreAppBaseUrl(), accessToken, nominationId);
    return { ok: true };
  } catch (error) {
    if (error instanceof ApiError) {
      return { error: "Couldn't dismiss this — please try again." };
    }
    return { error: "Something went wrong — please try again." };
  }
}
