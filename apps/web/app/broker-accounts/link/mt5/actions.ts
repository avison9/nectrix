"use server";

import { ApiError, linkMt4Account, linkMt5Account } from "@nectrix/api-client";
import type { ConnectionRole, MtLinkInput, MtLinkResult } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";

export async function linkMtAccountAction(
  platform: "MT5" | "MT4",
  input: MtLinkInput,
): Promise<MtLinkResult | { error: string; requiresTwoFactor?: boolean }> {
  const { accessToken } = await requireSession();
  try {
    return platform === "MT5"
      ? await linkMt5Account(coreAppBaseUrl(), accessToken, input)
      : await linkMt4Account(coreAppBaseUrl(), accessToken, input);
  } catch (error) {
    if (error instanceof ApiError) {
      const body = error.body as { error?: string } | null;
      if (body?.error === "two_factor_required") {
        return { error: "two_factor_required", requiresTwoFactor: true };
      }
      if (body?.error === "broker_account_already_linked") {
        return { error: "This login is already linked to your account." };
      }
      return { error: body?.error ?? "Linking failed — please try again." };
    }
    return { error: "Linking failed — please try again." };
  }
}

export type { ConnectionRole };
