"use server";

import { cookies } from "next/headers";
import { ApiError, refreshSession, verifyTwoFactor } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";

const isProduction = process.env.NODE_ENV === "production";

export interface VerifyTwoFactorState {
  error?: string;
}

/**
 * TICKET-116 — the settings-page enrollment flow's own verify action. Same shape as
 * broker-accounts/2fa-required's own verifyTwoFactorAction (route-scoped duplication, not a
 * cross-route import — matches this app's existing per-route actions.ts convention): verifies the
 * scanned code, then refreshes the session so the access_token cookie's `two_factor_enabled` claim
 * (a snapshot as of token-issue time) reflects reality without forcing a re-login.
 */
export async function verifyTwoFactorAction(totpCode: string): Promise<VerifyTwoFactorState> {
  const { accessToken } = await requireSession();

  try {
    await verifyTwoFactor(coreAppBaseUrl(), accessToken, totpCode);
  } catch (error) {
    if (error instanceof ApiError && error.status === 422) {
      return { error: "That code didn't match — check the time on your device and try again." };
    }
    return { error: "Verification failed — please try again." };
  }

  const jar = await cookies();
  const refreshToken = jar.get("refresh_token")?.value;
  if (refreshToken) {
    try {
      const result = await refreshSession(coreAppBaseUrl(), refreshToken);
      const cookieOptions = {
        httpOnly: true,
        sameSite: "lax" as const,
        secure: isProduction,
        path: "/",
      };
      jar.set("access_token", result.accessToken, { ...cookieOptions, maxAge: result.expiresIn });
      jar.set("refresh_token", result.refreshToken, {
        ...cookieOptions,
        maxAge: 60 * 60 * 24 * 30,
      });
    } catch {
      // Non-fatal — verification itself already succeeded server-side; worst case the next
      // request still carries the stale claim, resolved on the next login/refresh anyway.
    }
  }

  return {};
}
