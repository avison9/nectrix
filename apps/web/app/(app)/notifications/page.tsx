import { listNotifications, parseNotificationPayload } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";
import { NotificationCenterList, type NotificationCenterItem } from "./NotificationCenterList";

export default async function NotificationsPage() {
  const { accessToken } = await requireSession();
  const items = await listNotifications(coreAppBaseUrl(), accessToken);

  const initialItems: NotificationCenterItem[] = items.map((item) => {
    const parsed = parseNotificationPayload(item);
    return {
      id: item.id,
      eventType: item.eventType,
      title: parsed.title,
      body: parsed.body,
      createdAt: item.createdAt,
      readAt: item.readAt,
    };
  });

  return (
    <div className="mx-auto max-w-[680px]">
      <div className="mb-6">
        <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">
          Notifications
        </h1>
        <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
          Copied trades, connection issues, and account activity. Click a notification to mark it
          read.
        </p>
      </div>

      <div className="overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)]">
        <NotificationCenterList accessToken={accessToken} initialItems={initialItems} />
      </div>
    </div>
  );
}
