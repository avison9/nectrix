"use server";

import { ApiError, linkCtraderAccount, submitCtraderCallback } from "@nectrix/api-client";
import type { ConnectionRole, CtraderAccountOption } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";

export async function submitCallbackAction(
  code: string,
  state: string,
): Promise<{ linkSessionId: string; accounts: CtraderAccountOption[] } | { error: string }> {
  try {
    return await submitCtraderCallback(coreAppBaseUrl(), code, state);
  } catch (error) {
    if (error instanceof ApiError) {
      const body = error.body as { error?: string } | null;
      return { error: body?.error ?? "The cTrader authorization could not be completed." };
    }
    return { error: "The cTrader authorization could not be completed." };
  }
}

export async function linkCtraderAccountAction(input: {
  linkSessionId: string;
  ctidTraderAccountId: number;
  isLive: boolean;
  displayLabel: string;
  connectionRole: ConnectionRole;
  openedViaIbLinkId?: string;
  brokerName?: string;
}): Promise<{ id: string } | { error: string; requiresTwoFactor?: boolean }> {
  const { accessToken } = await requireSession();
  try {
    const account = await linkCtraderAccount(coreAppBaseUrl(), accessToken, input);
    return { id: account.id };
  } catch (error) {
    if (error instanceof ApiError) {
      const body = error.body as { error?: string } | null;
      if (body?.error === "two_factor_required") {
        return { error: "two_factor_required", requiresTwoFactor: true };
      }
      return { error: body?.error ?? "Linking failed — please try again." };
    }
    return { error: "Linking failed — please try again." };
  }
}
