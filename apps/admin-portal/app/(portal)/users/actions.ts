"use server";

import { cookies } from "next/headers";
import { revalidatePath } from "next/cache";
import {
  ApiError,
  createAdminUser,
  reinstateUser,
  searchUsers,
  suspendUser,
  type UserSummary,
} from "@nectrix/api-client";
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

/** TICKET-117 — the search input's own Server Action; a blank query returns every user. */
export async function searchUsersAction(query: string): Promise<UserSummary[]> {
  const jar = await cookies();
  const accessToken = jar.get("access_token")?.value;
  if (!accessToken) {
    return [];
  }
  return searchUsers(coreAppBaseUrl(), accessToken, query);
}

export interface UserStatusActionState {
  error?: string;
}

/**
 * ADMIN-only — a SUPPORT session's bearer token fails core-app's own
 * @PreAuthorize("hasRole('ADMIN')") with a real 403, not just a UI-hidden button (the caller
 * component only renders these buttons for ADMIN in the first place, but this is the real gate).
 */
export async function suspendUserAction(id: string): Promise<UserStatusActionState> {
  const jar = await cookies();
  const accessToken = jar.get("access_token")?.value;
  if (!accessToken) {
    return { error: "Your session has expired — please log in again." };
  }
  try {
    await suspendUser(coreAppBaseUrl(), accessToken, id);
  } catch (error) {
    if (error instanceof ApiError && error.status === 403) {
      return { error: "You don't have permission to suspend accounts." };
    }
    return { error: "Failed to suspend this account." };
  }
  revalidatePath("/users");
  revalidatePath(`/users/${id}`);
  return {};
}

export async function reinstateUserAction(id: string): Promise<UserStatusActionState> {
  const jar = await cookies();
  const accessToken = jar.get("access_token")?.value;
  if (!accessToken) {
    return { error: "Your session has expired — please log in again." };
  }
  try {
    await reinstateUser(coreAppBaseUrl(), accessToken, id);
  } catch (error) {
    if (error instanceof ApiError && error.status === 403) {
      return { error: "You don't have permission to reinstate accounts." };
    }
    return { error: "Failed to reinstate this account." };
  }
  revalidatePath("/users");
  revalidatePath(`/users/${id}`);
  return {};
}
