"use server";

import { revalidatePath } from "next/cache";
import { markNotificationRead } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";

export async function markNotificationReadAction(id: string): Promise<void> {
  const { accessToken } = await requireSession();
  await markNotificationRead(coreAppBaseUrl(), accessToken, id);
  // Topbar's unread badge is computed by AppShell in the (app) layout, not this page —
  // revalidate the root layout so it re-fetches the count on the transition triggered below.
  revalidatePath("/", "layout");
}
