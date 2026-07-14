"use client";

import { useEffect, useState } from "react";
import type { ConnectionStatus } from "@nectrix/domain-model";

const LABEL: Record<ConnectionStatus, string> = {
  CONNECTED: "Connected",
  DEGRADED: "Degraded",
  DISCONNECTED: "Disconnected",
  REAUTH_REQUIRED: "Reauth required",
  PENDING: "Pending",
};

const COLOR: Record<ConnectionStatus, string> = {
  CONNECTED: "bg-[var(--pos)]/15 text-[var(--pos)]",
  DEGRADED: "bg-amber-500/15 text-amber-600",
  DISCONNECTED: "bg-[var(--neg)]/15 text-[var(--neg)]",
  REAUTH_REQUIRED: "bg-[var(--neg)]/15 text-[var(--neg)]",
  PENDING: "bg-[var(--accent-2)] text-[var(--text-2)]",
};

const EVENT_TYPE_TO_STATUS: Record<string, ConnectionStatus> = {
  BROKER_CONNECTION_EVENT_TYPE_ESTABLISHED: "CONNECTED",
  BROKER_CONNECTION_EVENT_TYPE_DEGRADED: "DEGRADED",
  BROKER_CONNECTION_EVENT_TYPE_LOST: "DISCONNECTED",
  BROKER_CONNECTION_EVENT_TYPE_REAUTH_REQUIRED: "REAUTH_REQUIRED",
};

interface Props {
  brokerAccountId: string;
  initialStatus: ConnectionStatus;
  /**
   * Short-lived (15 min) access token, passed down from a Server Component
   * that already called requireSession() -- deliberately NOT read from the
   * httpOnly access_token cookie (client JS can't see that one by design).
   * This is the one, explicit, narrow exception to "the browser never talks
   * to core-app directly": the WS channel is bearer-token-authenticated, not
   * cookie-based (see WebSocketConfig's own Javadoc), so exposing this one
   * short-lived token to this one component's client bundle doesn't cross a
   * new trust boundary the way leaking a long-lived credential would.
   */
  accessToken: string;
}

/**
 * TICKET-110 — docs/14-api-specification.md §14.11's broker-connection.{id}
 * channel: opens a real WebSocket, subscribes, and updates live on a genuine
 * server push (no polling). Falls back to the server-rendered initialStatus
 * (a real refetch would require a page reload) if the socket never connects
 * or errors -- a real network/browser-compat safety net, not the primary path.
 */
export function ConnectionStatusBadge({ brokerAccountId, initialStatus, accessToken }: Props) {
  const [status, setStatus] = useState<ConnectionStatus>(initialStatus);
  const [live, setLive] = useState(false);

  useEffect(() => {
    const wsBaseUrl = process.env.NEXT_PUBLIC_CORE_APP_WS_URL;
    if (!wsBaseUrl) {
      return;
    }
    const socket = new WebSocket(`${wsBaseUrl}/ws/v1?access_token=${encodeURIComponent(accessToken)}`);

    socket.addEventListener("open", () => {
      setLive(true);
      socket.send(
        JSON.stringify({ action: "subscribe", channel: "broker-connection", brokerAccountId }),
      );
    });

    socket.addEventListener("message", (event) => {
      try {
        const payload = JSON.parse(event.data as string) as {
          brokerAccountId?: string;
          eventType?: string;
        };
        if (payload.brokerAccountId !== brokerAccountId || !payload.eventType) {
          return;
        }
        const mapped = EVENT_TYPE_TO_STATUS[payload.eventType];
        if (mapped) {
          setStatus(mapped);
        }
      } catch {
        // malformed frame, ignore
      }
    });

    socket.addEventListener("close", () => setLive(false));
    socket.addEventListener("error", () => setLive(false));

    return () => socket.close();
  }, [brokerAccountId, accessToken]);

  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-[11px] font-semibold ${COLOR[status]}`}
      title={live ? "Live updates connected" : "Showing last known status"}
    >
      <span
        className={`h-1.5 w-1.5 rounded-full ${live ? "bg-current" : "bg-current opacity-40"}`}
      />
      {LABEL[status]}
    </span>
  );
}
