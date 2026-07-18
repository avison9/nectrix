"use server";

import {
  ApiError,
  acknowledgeRisk,
  listCopyRelationshipTrades,
  pauseCopyRelationship,
  resumeCopyRelationship,
  signAgreement,
  stopCopyRelationship,
  updateCopySettings,
} from "@nectrix/api-client";
import type { CopiedTradesPage, CopyRelationship, CopySettingsInput } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";

type ActionResult = CopyRelationship | { error: string };

async function run(id: string, call: (baseUrl: string, accessToken: string, id: string) => Promise<CopyRelationship>): Promise<ActionResult> {
  const { accessToken } = await requireSession();
  try {
    return await call(coreAppBaseUrl(), accessToken, id);
  } catch (error) {
    if (error instanceof ApiError && error.status === 409) {
      const body = error.body as { error?: string } | null;
      return { error: body?.error ?? "That action isn't valid for the relationship's current status." };
    }
    return { error: "Something went wrong — please try again." };
  }
}

export async function acknowledgeRiskAction(id: string): Promise<ActionResult> {
  return run(id, acknowledgeRisk);
}

export async function signAgreementAction(id: string): Promise<ActionResult> {
  return run(id, signAgreement);
}

export async function pauseCopyRelationshipAction(id: string): Promise<ActionResult> {
  return run(id, pauseCopyRelationship);
}

export async function resumeCopyRelationshipAction(id: string): Promise<ActionResult> {
  return run(id, resumeCopyRelationship);
}

export async function stopCopyRelationshipAction(id: string): Promise<ActionResult> {
  return run(id, stopCopyRelationship);
}

export async function loadTradesPageAction(id: string, page: number): Promise<CopiedTradesPage> {
  const { accessToken } = await requireSession();
  return listCopyRelationshipTrades(coreAppBaseUrl(), accessToken, id, page);
}

export async function updateCopySettingsAction(
  id: string,
  input: CopySettingsInput,
): Promise<ActionResult> {
  const { accessToken } = await requireSession();
  try {
    return await updateCopySettings(coreAppBaseUrl(), accessToken, id, input);
  } catch {
    return { error: "Could not save these settings — please try again." };
  }
}
