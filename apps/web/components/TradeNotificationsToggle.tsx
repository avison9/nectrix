"use client";

import { useState, useTransition } from "react";
import { updateTradeNotificationsAction } from "@/app/(app)/profile/actions";

export function TradeNotificationsToggle({ initialEnabled }: { initialEnabled: boolean }) {
  const [enabled, setEnabled] = useState(initialEnabled);
  const [pending, startTransition] = useTransition();

  function toggle() {
    const next = !enabled;
    setEnabled(next);
    startTransition(async () => {
      try {
        await updateTradeNotificationsAction(next);
      } catch {
        setEnabled(!next); // revert on failure
      }
    });
  }

  return (
    <button
      type="button"
      onClick={toggle}
      disabled={pending}
      aria-pressed={enabled}
      aria-label="Trade notifications"
      className={`relative h-6 w-[42px] flex-none rounded-full transition-colors disabled:opacity-60 ${
        enabled ? "bg-[var(--accent)]" : "bg-[var(--border)]"
      }`}
    >
      <div
        className={`absolute top-0.5 h-5 w-5 rounded-full bg-white transition-transform ${
          enabled ? "translate-x-[22px]" : "translate-x-0.5"
        }`}
      />
    </button>
  );
}
