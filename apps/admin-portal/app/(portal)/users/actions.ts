"use server";

import { cookies } from "next/headers";
import { revalidatePath } from "next/cache";
import {
  ApiError,
  createAdminUser,
  deleteUser,
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

const PROVISIONABLE_ROLES = ["ADMIN", "SUPPORT", "MASTER", "FOLLOWER"] as const;
type ProvisionableRole = (typeof PROVISIONABLE_ROLES)[number];

/**
 * Backs TICKET-012 AC4 — ADMIN-gated both here (a SUPPORT/MASTER session's
 * cookie has no bearer token that would pass core-app's own
 * @PreAuthorize("hasRole('ADMIN')") check, so this fails server-side with a
 * real 403 from ApiError, not a UI-only restriction) and again at core-app
 * itself (AdminController#provisionUser — the real, authoritative gate; this
 * client-side list is kept in sync with PROVISIONABLE_ROLES there but isn't
 * itself the security boundary).
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
  if (!PROVISIONABLE_ROLES.includes(role as ProvisionableRole)) {
    return { error: `Role must be one of ${PROVISIONABLE_ROLES.join(", ")}.` };
  }

  try {
    await createAdminUser(coreAppBaseUrl(), accessToken, {
      email,
      password,
      displayName,
      role: role as ProvisionableRole,
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

/**
 * TICKET-117 — the search input's own Server Action; a blank query returns every user.
 *
 * <p>Bugfix follow-up — {@code status} is the status filter beside the search box
 * (ACTIVE/SUSPENDED/DELETED); blank means the default view (see searchUsers's own doc for what
 * that excludes).
 */
export async function searchUsersAction(query: string, status = ""): Promise<UserSummary[]> {
  const jar = await cookies();
  const accessToken = jar.get("access_token")?.value;
  if (!accessToken) {
    return [];
  }
  return searchUsers(coreAppBaseUrl(), accessToken, query, status);
}

export interface UserStatusActionState {
  error?: string;
  // TICKET-117 bugfix — the caller (UserActions) applies this directly to its own local state
  // instead of relying on router.refresh(), which does nothing for a client component's own
  // useState-held results (e.g. UserSearch's results table) — only the endpoint's own real
  // response, not a refetch, updates that state immediately.
  user?: UserSummary;
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
    const user = await suspendUser(coreAppBaseUrl(), accessToken, id);
    revalidatePath("/users");
    revalidatePath(`/users/${id}`);
    return { user };
  } catch (error) {
    if (error instanceof ApiError && error.status === 403) {
      return { error: "You don't have permission to suspend accounts." };
    }
    return { error: "Failed to suspend this account." };
  }
}

export async function reinstateUserAction(id: string): Promise<UserStatusActionState> {
  const jar = await cookies();
  const accessToken = jar.get("access_token")?.value;
  if (!accessToken) {
    return { error: "Your session has expired — please log in again." };
  }
  try {
    const user = await reinstateUser(coreAppBaseUrl(), accessToken, id);
    revalidatePath("/users");
    revalidatePath(`/users/${id}`);
    return { user };
  } catch (error) {
    if (error instanceof ApiError && error.status === 403) {
      return { error: "You don't have permission to reinstate accounts." };
    }
    return { error: "Failed to reinstate this account." };
  }
}

/** ADMIN-only server-side — see AdminController#deleteUser's own Javadoc for what "delete" means here. */
export async function deleteUserAction(id: string): Promise<UserStatusActionState> {
  const jar = await cookies();
  const accessToken = jar.get("access_token")?.value;
  if (!accessToken) {
    return { error: "Your session has expired — please log in again." };
  }
  try {
    const user = await deleteUser(coreAppBaseUrl(), accessToken, id);
    revalidatePath("/users");
    revalidatePath(`/users/${id}`);
    return { user };
  } catch (error) {
    if (error instanceof ApiError && error.status === 403) {
      return { error: "You don't have permission to delete accounts." };
    }
    return { error: "Failed to delete this account." };
  }
}
