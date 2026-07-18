"use client";

import { useEffect, useState, useTransition } from "react";
import { markNotificationReadAction } from "./actions";

export interface NotificationCenterItem {
  id: string;
  eventType: string;
  title: string;
  body: string;
  createdAt: string;
  readAt: string | null;
}

interface WsNotificationMessage {
  channel: "notifications";
  eventType: string;
  title: string;
  body: string;
}

/**
 * TICKET-116 — no literal mock section for this (the mock's own "Inbox" is an admin-portal support
 * inbox, a different concept — see this app's own scope-boundary notes); a real notification center
 * the Topbar's bell links to, built to match this app's established list-item conventions.
 * Live-pushed items (via the already-real `notifications.{userId}` WS channel, TICKET-115) are
 * synthesized with a client-generated id/createdAt — they become durable/markable only once a page
 * refresh re-fetches them from `notification_log` with their real id.
 */
export function NotificationCenterList({
  accessToken,
  initialItems,
}: {
  accessToken: string;
  initialItems: NotificationCenterItem[];
}) {
  const [items, setItems] = useState(initialItems);
  const [pending, startTransition] = useTransition();

  useEffect(() => {
    const wsBaseUrl = process.env.NEXT_PUBLIC_CORE_APP_WS_URL;
    if (!wsBaseUrl) return;
    const socket = new WebSocket(
      `${wsBaseUrl}/ws/v1?access_token=${encodeURIComponent(accessToken)}`,
    );

    socket.addEventListener("open", () => {
      socket.send(JSON.stringify({ action: "subscribe", channel: "notifications" }));
    });

    socket.addEventListener("message", (event) => {
      try {
        const payload: unknown = JSON.parse(event.data as string);
        if (
          typeof payload !== "object" ||
          payload === null ||
          (payload as { channel?: string }).channel !== "notifications"
        ) {
          return;
        }
        const msg = payload as WsNotificationMessage;
        setItems((prev) => [
          {
            id: `live-${Date.now()}`,
            eventType: msg.eventType,
            title: msg.title,
            body: msg.body,
            createdAt: new Date().toISOString(),
            readAt: null,
          },
          ...prev,
        ]);
      } catch {
        // malformed frame, ignore
      }
    });

    return () => socket.close();
  }, [accessToken]);

  function markRead(id: string) {
    if (id.startsWith("live-")) return; // not a real notification_log row yet
    startTransition(async () => {
      setItems((prev) =>
        prev.map((i) => (i.id === id ? { ...i, readAt: new Date().toISOString() } : i)),
      );
      await markNotificationReadAction(id);
    });
  }

  if (items.length === 0) {
    return (
      <p className="px-5 py-10 text-center text-[13px] text-[var(--text-2)]">
        You&apos;re all caught up — no notifications yet.
      </p>
    );
  }

  return (
    <ul className="flex flex-col">
      {items.map((item) => (
        <li
          key={item.id}
          onClick={() => markRead(item.id)}
          className={`flex cursor-pointer items-start justify-between gap-4 border-t border-[var(--border)] px-5 py-3.5 first:border-t-0 ${
            item.readAt ? "" : "bg-[var(--accent-2)]/30"
          }`}
        >
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2">
              {!item.readAt && <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-[var(--accent)]" />}
              <span className="text-[13.5px] font-semibold text-[var(--text)]">{item.title}</span>
            </div>
            {item.body && (
              <p className="mt-1 text-[12.5px] text-[var(--text-2)]">{item.body}</p>
            )}
          </div>
          <span className="shrink-0 text-[11px] text-[var(--text-3)]">
            {new Date(item.createdAt).toLocaleString()}
          </span>
        </li>
      ))}
      {pending && <span className="sr-only">Updating…</span>}
    </ul>
  );
}
