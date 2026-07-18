"use server";

import { markNotificationRead } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";

export async function markNotificationReadAction(id: string): Promise<void> {
  const { accessToken } = await requireSession();
  await markNotificationRead(coreAppBaseUrl(), accessToken, id);
}
