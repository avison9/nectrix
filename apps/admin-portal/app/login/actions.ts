"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { ApiError, login } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";

export interface LoginActionState {
  error?: string;
}

const isProduction = process.env.NODE_ENV === "production";

export async function loginAction(
  _prevState: LoginActionState,
  formData: FormData,
): Promise<LoginActionState> {
  const email = String(formData.get("email") ?? "");
  const password = String(formData.get("password") ?? "");
  const totpCodeRaw = formData.get("totpCode");
  const totpCode = totpCodeRaw ? String(totpCodeRaw) : undefined;

  try {
    const result = await login(coreAppBaseUrl(), email, password, totpCode);
    const jar = await cookies();
    const cookieOptions = {
      httpOnly: true,
      sameSite: "lax" as const,
      secure: isProduction,
      path: "/",
    };
    jar.set("access_token", result.accessToken, {
      ...cookieOptions,
      maxAge: result.expiresIn,
    });
    // Refresh tokens outlive the access token considerably (TICKET-005:
    // rotate-on-use, revoked-on-reuse) — 30 days matches sessions' own TTL
    // convention elsewhere in this codebase.
    jar.set("refresh_token", result.refreshToken, {
      ...cookieOptions,
      maxAge: 60 * 60 * 24 * 30,
    });
  } catch (error) {
    if (error instanceof ApiError) {
      const body = error.body as { error?: string } | null;
      if (body?.error === "totp_required") {
        return { error: "totp_required" };
      }
      return { error: "Invalid email or password." };
    }
    return { error: "Login failed — please try again." };
  }

  redirect("/audit-log");
}

export async function logoutAction() {
  const jar = await cookies();
  jar.delete("access_token");
  jar.delete("refresh_token");
  redirect("/login");
}
