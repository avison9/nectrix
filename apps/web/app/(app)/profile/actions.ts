"use server";

import { updateNotificationPreference } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";

/**
 * TICKET-115 — the one real toggle the mock shows (`Trade notifications`, "Push alerts when a
 * copied trade opens") — wired to the actual preferences API for `copied_trade.opened`/`PUSH`.
 */
export async function updateTradeNotificationsAction(enabled: boolean): Promise<void> {
  const { accessToken } = await requireSession();
  await updateNotificationPreference(coreAppBaseUrl(), accessToken, {
    eventType: "copied_trade.opened",
    channel: "PUSH",
    enabled,
  });
}
