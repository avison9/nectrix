"use client";

import { useEffect, useRef, useState } from "react";
import type { CopiedTrade } from "@nectrix/api-client";
import { refetchOpenPositionsAction } from "./live-positions-actions";

export interface FeedSubscription {
  /** {@code positions.{brokerAccountId}} for a Master's own account, {@code copy-relationships.{id}} for a Follower's relationship. */
  channel: "positions" | "copy-relationships";
  id: string;
  label: string;
}

const POLL_MS = 12000;

/**
 * TICKET-124 — replaces the previous WebSocket event log with an actual live position book:
 * fetched on mount (via `initialPositions`, server-rendered so there's no blank-then-populate
 * flash), kept current by the SAME interval-poll mechanism trade-history/LiveRefresh.tsx uses,
 * plus an immediate refetch whenever the WebSocket signals a trade lifecycle event happened (the
 * socket carries lifecycle events only, never price ticks, so it alone can't keep unrealized P&L
 * "live" between events — polling is what actually does that). Reuses
 * UnrealizedPnlEnrichmentService's own output (via refetchOpenPositionsAction) rather than a
 * second, independent computation.
 */
export function LivePositionsFeed({
  accessToken,
  subscriptions,
  role,
  initialPositions,
}: {
  accessToken: string;
  subscriptions: FeedSubscription[];
  role: "follower" | "master";
  initialPositions: CopiedTrade[];
}) {
  const [positions, setPositions] = useState<CopiedTrade[]>(initialPositions);
  const [live, setLive] = useState(false);
  const refetching = useRef(false);

  async function refetch() {
    if (refetching.current) return;
    refetching.current = true;
    try {
      setPositions(await refetchOpenPositionsAction(role));
    } catch {
      // best-effort -- keep showing the last-known list rather than clearing it on a blip
    } finally {
      refetching.current = false;
    }
  }

  useEffect(() => {
    const interval = setInterval(refetch, POLL_MS);
    return () => clearInterval(interval);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [role]);

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

    // Any message on either channel means a real state transition happened -- refetch rather
    // than trying to hand-patch local state from a partial wire payload.
    socket.addEventListener("message", () => refetch());
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
      {positions.length === 0 ? (
        <p className="px-5 py-8 text-center text-[13px] text-[var(--text-2)]">
          No open positions right now.
        </p>
      ) : (
        <ul className="flex flex-col">
          {positions.map((p) => (
            <li
              key={p.id}
              className="flex items-center justify-between gap-3 border-b border-[var(--border)] px-5 py-2.5 last:border-b-0"
            >
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <span className="font-mono text-[13px] font-semibold text-[var(--text)]">
                    {p.canonicalSymbol}
                  </span>
                  <span
                    className={`text-[11.5px] font-semibold ${
                      p.direction === "BUY" ? "text-[var(--pos)]" : "text-[var(--neg)]"
                    }`}
                  >
                    {p.direction}
                  </span>
                </div>
                <div className="text-[11.5px] text-[var(--text-3)]">
                  {p.computedVolumeLots} lots
                </div>
              </div>
              <span
                className={`shrink-0 font-mono text-[13px] font-semibold ${
                  p.unrealizedPnl === null
                    ? "text-[var(--text-3)]"
                    : p.unrealizedPnl >= 0
                      ? "text-[var(--pos)]"
                      : "text-[var(--neg)]"
                }`}
              >
                {p.unrealizedPnl === null ? "—" : p.unrealizedPnl.toFixed(2)}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
