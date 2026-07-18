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
 * Verifies the scanned code, then refreshes the session: the access_token
 * cookie's JWT still carries two_factor_enabled=false (JwtService's own
 * claim is a snapshot as of token-issue time, per that class's own Javadoc),
 * so a bare "verification succeeded" would leave the user stuck bouncing
 * back to this same gate on their next linking attempt. Uses the existing
 * refresh_token cookie for a silent refresh — no forced re-login.
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
      // Non-fatal — 2FA verification itself already succeeded server-side;
      // worst case the user's next request still carries the stale claim
      // and re-lands on this same gate, where a page reload re-issues a
      // fresh token anyway (login itself always embeds the current value).
    }
  }

  return {};
}
