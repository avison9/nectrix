"use server";

import { getCtraderAuthorizeUrl } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";

export async function getAuthorizeUrlAction(): Promise<{ authorizeUrl: string } | { error: string }> {
  const { accessToken } = await requireSession();
  try {
    return await getCtraderAuthorizeUrl(coreAppBaseUrl(), accessToken);
  } catch {
    return { error: "Could not reach cTrader — please try again." };
  }
}
