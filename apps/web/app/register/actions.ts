"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import {
  ApiError,
  login,
  registerUser,
  startSubscriptionCheckout,
  type SubscriptionPlanCode,
} from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";

export interface RegisterActionState {
  error?: string;
}

const isProduction = process.env.NODE_ENV === "production";

/**
 * TICKET-114 — register, then log in with the same credentials to establish a session (reusing
 * @nectrix/api-client's login() directly, not app/login/actions.ts's loginAction — that action
 * ends in its own unconditional redirect(), which would fire before this action gets to the
 * mandatory Stripe Checkout step below), then always start a Checkout session (every plan
 * requires a card on file, including the entry tier — there's no free/skip path) and hand the
 * browser off to Stripe's hosted page.
 */
export async function registerAction(
  _prevState: RegisterActionState,
  formData: FormData,
): Promise<RegisterActionState> {
  const email = String(formData.get("email") ?? "");
  const password = String(formData.get("password") ?? "");
  const displayName = String(formData.get("displayName") ?? "");
  const planCode = String(formData.get("planCode") ?? "") as SubscriptionPlanCode;

  const baseUrl = coreAppBaseUrl();
  let checkoutUrl: string;
  try {
    await registerUser(baseUrl, { email, password, displayName });
    const session = await login(baseUrl, email, password);

    const jar = await cookies();
    const cookieOptions = {
      httpOnly: true,
      sameSite: "lax" as const,
      secure: isProduction,
      path: "/",
    };
    jar.set("access_token", session.accessToken, {
      ...cookieOptions,
      maxAge: session.expiresIn,
    });
    jar.set("refresh_token", session.refreshToken, {
      ...cookieOptions,
      maxAge: 60 * 60 * 24 * 30,
    });

    const checkout = await startSubscriptionCheckout(baseUrl, session.accessToken, planCode);
    checkoutUrl = checkout.checkoutUrl;
  } catch (error) {
    if (error instanceof ApiError) {
      const body = error.body as { error?: string } | null;
      if (body?.error === "email_already_registered") {
        return { error: "An account with that email already exists." };
      }
      return { error: "Registration failed — please try again." };
    }
    return { error: "Registration failed — please try again." };
  }

  redirect(checkoutUrl);
}
