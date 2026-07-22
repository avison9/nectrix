"use server";

import {
  ApiError,
  confirmBrokerFeeReportDeducted,
  confirmBrokerFeeReportPaid,
  generateBrokerFeeReport,
  sendBrokerFeeReport,
} from "@nectrix/api-client";
import type { BrokerFeeReport } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";

type ActionResult = { ok: true } | { error: string };

/** MASTER-only — a non-MASTER session's own real 403 from core-app is what actually gates this. */
export async function generateBrokerFeeReportAction(
  _prevState: ActionResult | null,
  formData: FormData,
): Promise<ActionResult> {
  const { accessToken } = await requireSession();
  const brokerType = String(formData.get("brokerType") ?? "");
  const periodStart = String(formData.get("periodStart") ?? "");
  const periodEnd = String(formData.get("periodEnd") ?? "");
  try {
    await generateBrokerFeeReport(coreAppBaseUrl(), accessToken, {
      brokerType,
      periodStart: new Date(periodStart).toISOString(),
      periodEnd: new Date(periodEnd).toISOString(),
    });
    return { ok: true };
  } catch (error) {
    if (error instanceof ApiError) {
      const body = error.body as { error?: string } | null;
      if (body?.error === "no_pending_fees_to_report") {
        return { error: "No pending fees for this broker in that period — nothing to report." };
      }
      if (body?.error === "broker_fee_report_already_exists_for_period") {
        return { error: "A report for this broker and period already exists." };
      }
      if (body?.error === "master_profile_required") {
        return { error: "Set up your Master profile before generating a fee report." };
      }
      return { error: "Couldn't generate this report — please try again." };
    }
    return { error: "Something went wrong — please try again." };
  }
}

async function runAction(
  id: string,
  call: (baseUrl: string, accessToken: string, id: string) => Promise<BrokerFeeReport>,
): Promise<BrokerFeeReport | { error: string }> {
  const { accessToken } = await requireSession();
  try {
    return await call(coreAppBaseUrl(), accessToken, id);
  } catch (error) {
    if (error instanceof ApiError && error.status === 409) {
      return { error: "That action isn't valid for this report's current status." };
    }
    return { error: "Something went wrong — please try again." };
  }
}

export async function sendBrokerFeeReportAction(id: string) {
  return runAction(id, sendBrokerFeeReport);
}

export async function confirmBrokerFeeReportDeductedAction(id: string) {
  return runAction(id, confirmBrokerFeeReportDeducted);
}

export async function confirmBrokerFeeReportPaidAction(id: string) {
  return runAction(id, confirmBrokerFeeReportPaid);
}
