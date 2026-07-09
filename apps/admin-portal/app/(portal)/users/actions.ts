"use server";

import { cookies } from "next/headers";
import { revalidatePath } from "next/cache";
import { ApiError, createAdminUser } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";

export interface ProvisionUserActionState {
  error?: string;
  success?: string;
}

/**
 * Backs TICKET-012 AC4 — ADMIN-gated both here (a SUPPORT/MASTER session's
 * cookie has no bearer token that would pass core-app's own
 * @PreAuthorize("hasRole('ADMIN')") check, so this fails server-side with a
 * real 403 from ApiError, not a UI-only restriction) and again at core-app
 * itself (AdminController#provisionUser).
 */
export async function provisionUserAction(
  _prevState: ProvisionUserActionState,
  formData: FormData,
): Promise<ProvisionUserActionState> {
  const jar = await cookies();
  const accessToken = jar.get("access_token")?.value;
  if (!accessToken) {
    return { error: "Your session has expired — please log in again." };
  }

  const email = String(formData.get("email") ?? "");
  const password = String(formData.get("password") ?? "");
  const displayName = String(formData.get("displayName") ?? "");
  const role = String(formData.get("role") ?? "");
  if (role !== "ADMIN" && role !== "SUPPORT") {
    return { error: "Role must be Admin or Support." };
  }

  try {
    await createAdminUser(coreAppBaseUrl(), accessToken, {
      email,
      password,
      displayName,
      role,
    });
  } catch (error) {
    if (error instanceof ApiError && error.status === 403) {
      return { error: "You don't have permission to provision accounts." };
    }
    return { error: "Failed to provision account — check the details and try again." };
  }

  revalidatePath("/audit-log");
  return { success: `${email} was provisioned as ${role}.` };
}
