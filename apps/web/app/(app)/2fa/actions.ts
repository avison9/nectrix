"use server";

import { cookies } from "next/headers";
import { ApiError, disableTwoFactor, refreshSession, verifyTwoFactor } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";

const isProduction = process.env.NODE_ENV === "production";

export interface VerifyTwoFactorState {
  error?: string;
}

/**
 * Refreshes the access_token cookie so its `two_factor_enabled` claim (a snapshot as of
 * token-issue time) reflects reality immediately, without forcing a re-login — same reasoning as
 * {@link verifyTwoFactorAction}'s own refresh step below.
 */
async function refreshSessionCookie() {
  const jar = await cookies();
  const refreshToken = jar.get("refresh_token")?.value;
  if (!refreshToken) {
    return;
  }
  try {
    const result = await refreshSession(coreAppBaseUrl(), refreshToken);
    const cookieOptions = {
      httpOnly: true,
      sameSite: "lax" as const,
      secure: isProduction,
      path: "/",
    };
    jar.set("access_token", result.accessToken, { ...cookieOptions, maxAge: result.expiresIn });
    jar.set("refresh_token", result.refreshToken, { ...cookieOptions, maxAge: 60 * 60 * 24 * 30 });
  } catch {
    // Non-fatal — same reasoning as verifyTwoFactorAction's own refresh step.
  }
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

  await refreshSessionCookie();
  return {};
}

/**
 * TICKET-117 bugfix — the settings page's "Disable two-factor authentication" button used to be
 * permanently inert ("no backend endpoint exists for it"); this is its real action, wired to the
 * new /2fa/disable endpoint.
 */
export async function disableTwoFactorAction(totpCode: string): Promise<VerifyTwoFactorState> {
  const { accessToken } = await requireSession();

  try {
    await disableTwoFactor(coreAppBaseUrl(), accessToken, totpCode);
  } catch (error) {
    if (error instanceof ApiError && error.status === 422) {
      return { error: "That code didn't match — check the time on your device and try again." };
    }
    return { error: "Couldn't disable two-factor authentication — please try again." };
  }

  await refreshSessionCookie();
  return {};
}
