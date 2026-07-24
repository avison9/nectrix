"use server";

import { cookies } from "next/headers";
import { revalidatePath } from "next/cache";
import { ApiError, restartEngine, stopEngine, startEngine } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";

export interface EngineControlActionState {
  error?: string;
}

/**
 * ADMIN-only server-side — core-app's own @PreAuthorize("hasRole('ADMIN')") is the real gate; this
 * mirrors suspendUserAction/deleteUserAction's own error-mapping convention. A disabled
 * service-control bean server-side surfaces as ApiError(409); a docker command that itself failed
 * (container not found, daemon unreachable, non-zero exit) surfaces as ApiError(502) — both are
 * real, distinct outcomes, not collapsed into one generic message.
 */
async function controlEngine(
  serviceId: string,
  action: (baseUrl: string, accessToken: string, serviceId: string) => Promise<void>,
  verb: string,
): Promise<EngineControlActionState> {
  const jar = await cookies();
  const accessToken = jar.get("access_token")?.value;
  if (!accessToken) {
    return { error: "Your session has expired — please log in again." };
  }
  try {
    await action(coreAppBaseUrl(), accessToken, serviceId);
  } catch (error) {
    if (error instanceof ApiError && error.status === 403) {
      return { error: "You don't have permission to control engines." };
    }
    if (error instanceof ApiError && error.status === 409) {
      return { error: "Service control is disabled on this environment." };
    }
    if (error instanceof ApiError && error.status === 502) {
      const body = error.body as { error?: string } | null;
      return { error: body?.error ?? `Failed to ${verb} ${serviceId}.` };
    }
    return { error: `Failed to ${verb} ${serviceId}.` };
  }
  revalidatePath("/engine-control");
  return {};
}

export async function restartEngineAction(serviceId: string): Promise<EngineControlActionState> {
  return controlEngine(serviceId, restartEngine, "restart");
}

export async function stopEngineAction(serviceId: string): Promise<EngineControlActionState> {
  return controlEngine(serviceId, stopEngine, "stop");
}

export async function startEngineAction(serviceId: string): Promise<EngineControlActionState> {
  return controlEngine(serviceId, startEngine, "start");
}
