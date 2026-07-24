"use server";

import {
  ApiError,
  archiveAndDeleteBrokerAccount,
  deleteBrokerAccount,
  disconnectBrokerAccount,
  reconnectBrokerAccount,
} from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";

type ActionResult = { ok: true } | { error: string };

/**
 * TICKET-101 follow-up — this used to actually DELETE the account (a real mislabeling caught
 * during review: a "Disconnect" button that silently removed the row). Now does what it says —
 * flips connection_status to DISCONNECTED, keeping the row. Deletion is a separate, explicit
 * follow-up action (deleteBrokerAccountAction below), only enabled once disconnected.
 */
export async function disconnectBrokerAccountAction(id: string): Promise<ActionResult> {
  const { accessToken } = await requireSession();
  try {
    await disconnectBrokerAccount(coreAppBaseUrl(), accessToken, id);
    return { ok: true };
  } catch (error) {
    if (error instanceof ApiError) {
      const body = error.body as { error?: string } | null;
      return { error: body?.error ?? "Couldn't disconnect this account — please try again." };
    }
    return { error: "Something went wrong — please try again." };
  }
}

/**
 * Bugfix — the reverse of {@link disconnectBrokerAccountAction}: a disconnected account previously
 * had no self-service way back short of deleting and fully re-linking from scratch via OAuth.
 */
export async function reconnectBrokerAccountAction(id: string): Promise<ActionResult> {
  const { accessToken } = await requireSession();
  try {
    await reconnectBrokerAccount(coreAppBaseUrl(), accessToken, id);
    return { ok: true };
  } catch (error) {
    if (error instanceof ApiError) {
      const body = error.body as { error?: string } | null;
      return { error: body?.error ?? "Couldn't reconnect this account — please try again." };
    }
    return { error: "Something went wrong — please try again." };
  }
}

export async function deleteBrokerAccountAction(id: string): Promise<ActionResult> {
  const { accessToken } = await requireSession();
  try {
    await deleteBrokerAccount(coreAppBaseUrl(), accessToken, id);
    return { ok: true };
  } catch (error) {
    if (error instanceof ApiError) {
      const body = error.body as { error?: string } | null;
      if (body?.error === "broker_account_not_disconnected") {
        return { error: "Disconnect this account before deleting it." };
      }
      if (body?.error === "broker_account_in_use") {
        // TICKET-101 follow-up — this used to dead-end here (there was no real way to remove an
        // account with trade/commission history). Now it isn't a dead end: fall back to
        // archiving everything referencing this account to a durable blob first, then deleting
        // it and the account row for real. See BrokerAccountArchivalOrchestrator's own Javadoc.
        return archiveAndDelete(id);
      }
      return { error: "Couldn't delete this account — please try again." };
    }
    return { error: "Something went wrong — please try again." };
  }
}

async function archiveAndDelete(id: string): Promise<ActionResult> {
  const { accessToken } = await requireSession();
  try {
    await archiveAndDeleteBrokerAccount(coreAppBaseUrl(), accessToken, id);
    return { ok: true };
  } catch (error) {
    if (error instanceof ApiError) {
      const body = error.body as { error?: string } | null;
      if (body?.error === "broker_account_not_ready_for_archival") {
        return { error: "Disconnect this account before deleting it." };
      }
      if (body?.error === "master_primary_broker_account_required") {
        // Bugfix — distinct from the case above: this account IS disconnected, but it's this
        // Master's only account eligible to act as one, so deleting it isn't possible until they
        // have another. Disconnecting again wouldn't help, so the message must say so.
        return {
          error:
            "This is your only broker account able to act as Master — link another broker account before deleting this one.",
        };
      }
      return { error: "Couldn't archive and delete this account — please try again." };
    }
    return { error: "Something went wrong — please try again." };
  }
}
