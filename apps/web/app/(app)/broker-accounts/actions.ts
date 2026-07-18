"use server";

import { ApiError, deleteBrokerAccount } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";

type ActionResult = { ok: true } | { error: string };

export async function disconnectBrokerAccountAction(id: string): Promise<ActionResult> {
  const { accessToken } = await requireSession();
  try {
    await deleteBrokerAccount(coreAppBaseUrl(), accessToken, id);
    return { ok: true };
  } catch (error) {
    if (error instanceof ApiError) {
      const body = error.body as { error?: string } | null;
      return { error: body?.error ?? "Couldn't disconnect this account — please try again." };
    }
    return { error: "Something went wrong — please try again." };
  }
}
