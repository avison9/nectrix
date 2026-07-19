"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { ApiError, acceptInvite } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";

export interface AcceptInviteActionState {
  error?: string;
}

const isProduction = process.env.NODE_ENV === "production";

/** Mirrors app/login/actions.ts's loginAction almost exactly — same cookie shape, same session semantics. */
export async function acceptInviteAction(
  _prevState: AcceptInviteActionState,
  formData: FormData,
): Promise<AcceptInviteActionState> {
  const token = String(formData.get("token") ?? "");
  const password = String(formData.get("password") ?? "");

  try {
    const result = await acceptInvite(coreAppBaseUrl(), { token, password });
    const jar = await cookies();
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
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.status === 400) {
        return { error: "This invitation is invalid or has expired." };
      }
      if (error.status === 429) {
        return { error: "Too many attempts — please wait a moment and try again." };
      }
    }
    return { error: "Couldn't accept this invitation — please try again." };
  }

  redirect("/onboarding");
}
