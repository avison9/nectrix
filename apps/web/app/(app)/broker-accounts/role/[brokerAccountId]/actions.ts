"use server";

import { patchBrokerAccount } from "@nectrix/api-client";
import type { BrokerAccountSummary, ConnectionRole } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";

export async function setConnectionRoleAction(
  brokerAccountId: string,
  connectionRole: ConnectionRole,
): Promise<BrokerAccountSummary | { error: string }> {
  const { accessToken } = await requireSession();
  try {
    return await patchBrokerAccount(coreAppBaseUrl(), accessToken, brokerAccountId, {
      connectionRole,
    });
  } catch {
    return { error: "Could not update the account role — please try again." };
  }
}
