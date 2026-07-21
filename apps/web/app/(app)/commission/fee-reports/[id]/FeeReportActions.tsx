"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import type { BrokerFeeReport } from "@nectrix/api-client";
import {
  confirmBrokerFeeReportDeductedAction,
  confirmBrokerFeeReportPaidAction,
  sendBrokerFeeReportAction,
} from "../actions";

/**
 * AC5 — these buttons are only ever a convenience: core-app itself rejects any transition invalid
 * for the report's current status (see BrokerFeeReportService's own requireStatus), so hiding the
 * wrong button here is a UX nicety, not the actual gate.
 */
export function FeeReportActions({ report }: { report: BrokerFeeReport }) {
  const router = useRouter();
  const [error, setError] = useState<string | undefined>();
  const [pending, startTransition] = useTransition();

  function run(action: (id: string) => Promise<BrokerFeeReport | { error: string }>) {
    setError(undefined);
    startTransition(async () => {
      const result = await action(report.id);
      if ("error" in result) {
        setError(result.error);
        return;
      }
      router.refresh();
    });
  }

  return (
    <div className="flex flex-col gap-3">
      {report.status === "DRAFT" && (
        <div className="rounded-[12px] border border-amber-500/30 bg-amber-500/10 p-4">
          <p className="text-[13.5px] font-medium text-[var(--text)]">
            Review the report document, then mark it sent once you've delivered it to your broker.
          </p>
          <button
            type="button"
            disabled={pending}
            onClick={() => run(sendBrokerFeeReportAction)}
            className="mt-3 h-10 rounded-[10px] bg-[var(--accent)] px-4 text-[13px] font-semibold text-white disabled:opacity-60"
          >
            {pending ? "Submitting…" : "Mark as sent"}
          </button>
        </div>
      )}

      {report.status === "SENT" && (
        <div className="rounded-[12px] border border-amber-500/30 bg-amber-500/10 p-4">
          <p className="text-[13.5px] font-medium text-[var(--text)]">
            Once your broker confirms they've deducted these fees, confirm it here.
          </p>
          <button
            type="button"
            disabled={pending}
            onClick={() => run(confirmBrokerFeeReportDeductedAction)}
            className="mt-3 h-10 rounded-[10px] bg-[var(--accent)] px-4 text-[13px] font-semibold text-white disabled:opacity-60"
          >
            {pending ? "Submitting…" : "Confirm deducted"}
          </button>
        </div>
      )}

      {report.status === "BROKER_CONFIRMED_DEDUCTED" && (
        <div className="rounded-[12px] border border-amber-500/30 bg-amber-500/10 p-4">
          <p className="text-[13.5px] font-medium text-[var(--text)]">
            Once you've received payout for these fees, confirm it here.
          </p>
          <button
            type="button"
            disabled={pending}
            onClick={() => run(confirmBrokerFeeReportPaidAction)}
            className="mt-3 h-10 rounded-[10px] bg-[var(--accent)] px-4 text-[13px] font-semibold text-white disabled:opacity-60"
          >
            {pending ? "Submitting…" : "Confirm paid"}
          </button>
        </div>
      )}

      {report.status === "BROKER_CONFIRMED_PAID" && (
        <p className="text-[13px] text-[var(--text-2)]">
          This report is fully settled — sent, deducted, and paid.
        </p>
      )}

      {error && <p className="text-[12.5px] text-[var(--neg)]">{error}</p>}
    </div>
  );
}
