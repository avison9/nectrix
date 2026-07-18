"use client";

import { useEffect, useState } from "react";

export interface FeedSubscription {
  /** {@code positions.{brokerAccountId}} for a Master's own account, {@code copy-relationships.{id}} for a Follower's relationship. */
  channel: "positions" | "copy-relationships";
  id: string;
  label: string;
}

interface FeedItem {
  key: string;
  label: string;
  symbol: string | null;
  eventType: string;
  detail: string;
  time: number;
}

/** Mirrors bootstrap's TradeSignalPositionConsumer PositionUpdateMessage JSON shape. */
interface PositionsWireMessage {
  channel: "positions";
  brokerAccountId: string;
  eventType: string;
  position?: { symbol?: string; direction?: string; volumeLots?: number };
}

/**
 * Mirrors bootstrap's copy-relationships channel wire shapes — either CopiedTradePositionConsumer's
 * trade_update or CopyRelationshipService.reloadAndPublish's status_changed (see this session's own
 * TICKET-116 project memory for both producers) — same channel, multiplexed by `type`.
 */
interface CopyRelationshipWireMessage {
  channel: "copy-relationships";
  type: "trade_update" | "status_changed";
  copyRelationshipId: string;
  eventType?: string;
  symbol?: string | null;
  volumeLots?: number | null;
  status?: string;
}

function toFeedItem(
  payload: unknown,
  subscriptions: FeedSubscription[],
): FeedItem | null {
  if (typeof payload !== "object" || payload === null || !("channel" in payload)) {
    return null;
  }
  const now = Date.now();
  if (payload.channel === "positions") {
    const msg = payload as PositionsWireMessage;
    const sub = subscriptions.find((s) => s.channel === "positions" && s.id === msg.brokerAccountId);
    if (!sub) return null;
    return {
      key: `${msg.brokerAccountId}-${now}`,
      label: sub.label,
      symbol: msg.position?.symbol ?? null,
      eventType: msg.eventType,
      detail: msg.position
        ? `${msg.position.direction ?? ""} ${msg.position.volumeLots ?? ""} lots`.trim()
        : "",
      time: now,
    };
  }
  if (payload.channel === "copy-relationships") {
    const msg = payload as CopyRelationshipWireMessage;
    const sub = subscriptions.find(
      (s) => s.channel === "copy-relationships" && s.id === msg.copyRelationshipId,
    );
    if (!sub) return null;
    if (msg.type === "status_changed") {
      return {
        key: `${msg.copyRelationshipId}-${now}`,
        label: sub.label,
        symbol: null,
        eventType: `Status → ${msg.status}`,
        detail: "",
        time: now,
      };
    }
    return {
      key: `${msg.copyRelationshipId}-${now}`,
      label: sub.label,
      symbol: msg.symbol ?? null,
      eventType: msg.eventType ?? "update",
      detail: msg.volumeLots != null ? `${msg.volumeLots} lots` : "",
      time: now,
    };
  }
  return null;
}

/**
 * TICKET-116 — docs/14-api-specification.md §14.11's positions/copy-relationships channels: a
 * live, no-polling feed of position/status activity. Same real-WebSocket-with-fallback shape as
 * ConnectionStatusBadge (TICKET-110) — falls back to "no live updates yet" if the socket never
 * connects, never a page reload requirement.
 */
export function LivePositionsFeed({
  accessToken,
  subscriptions,
}: {
  accessToken: string;
  subscriptions: FeedSubscription[];
}) {
  const [items, setItems] = useState<FeedItem[]>([]);
  const [live, setLive] = useState(false);

  useEffect(() => {
    const wsBaseUrl = process.env.NEXT_PUBLIC_CORE_APP_WS_URL;
    if (!wsBaseUrl || subscriptions.length === 0) {
      return;
    }
    const socket = new WebSocket(
      `${wsBaseUrl}/ws/v1?access_token=${encodeURIComponent(accessToken)}`,
    );

    socket.addEventListener("open", () => {
      setLive(true);
      for (const sub of subscriptions) {
        const frame =
          sub.channel === "positions"
            ? { action: "subscribe", channel: "positions", brokerAccountId: sub.id }
            : { action: "subscribe", channel: "copy-relationships", id: sub.id };
        socket.send(JSON.stringify(frame));
      }
    });

    socket.addEventListener("message", (event) => {
      try {
        const payload: unknown = JSON.parse(event.data as string);
        const item = toFeedItem(payload, subscriptions);
        if (item) {
          setItems((prev) => [item, ...prev].slice(0, 20));
        }
      } catch {
        // malformed frame, ignore
      }
    });

    socket.addEventListener("close", () => setLive(false));
    socket.addEventListener("error", () => setLive(false));

    return () => socket.close();
    // subscriptions is server-provided and stable for the lifetime of this component instance.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [accessToken]);

  return (
    <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)]">
      <div className="flex items-center justify-between border-b border-[var(--border)] px-5 py-3.5">
        <span className="text-[14px] font-semibold text-[var(--text)]">Live activity</span>
        <span
          className="flex items-center gap-1.5 text-[11px] text-[var(--text-3)]"
          title={live ? "Live updates connected" : "Not connected"}
        >
          <span
            className={`h-1.5 w-1.5 rounded-full ${live ? "bg-[var(--pos)]" : "bg-[var(--text-3)] opacity-40"}`}
          />
          {live ? "Live" : "Offline"}
        </span>
      </div>
      {items.length === 0 ? (
        <p className="px-5 py-8 text-center text-[13px] text-[var(--text-2)]">
          No activity yet — updates will appear here in real time.
        </p>
      ) : (
        <ul className="flex flex-col">
          {items.map((item) => (
            <li
              key={item.key}
              className="flex items-center justify-between gap-3 border-b border-[var(--border)] px-5 py-2.5 last:border-b-0"
            >
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  {item.symbol && (
                    <span className="font-mono text-[13px] font-semibold text-[var(--text)]">
                      {item.symbol}
                    </span>
                  )}
                  <span className="truncate text-[12px] text-[var(--text-2)]">{item.eventType}</span>
                </div>
                <div className="text-[11.5px] text-[var(--text-3)]">
                  {item.label}
                  {item.detail ? ` · ${item.detail}` : ""}
                </div>
              </div>
              <span className="shrink-0 text-[11px] text-[var(--text-3)]">
                {new Date(item.time).toLocaleTimeString()}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
