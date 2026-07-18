"use client";

import { useState, useTransition } from "react";
import type { CopiedTradesPage } from "@nectrix/api-client";
import { loadTradesPageAction } from "./actions";

export function TradesHistory({
  relationshipId,
  initialPage,
}: {
  relationshipId: string;
  initialPage: CopiedTradesPage;
}) {
  const [tradesPage, setTradesPage] = useState(initialPage);
  const [pending, startTransition] = useTransition();

  function goToPage(page: number) {
    startTransition(async () => {
      setTradesPage(await loadTradesPageAction(relationshipId, page));
    });
  }

  const lastPage = Math.max(0, Math.ceil(tradesPage.total / tradesPage.pageSize) - 1);

  return (
    <div className="mt-8">
      <h2 className="text-[15px] font-semibold text-[var(--text)]">Trade history</h2>

      {tradesPage.trades.length === 0 ? (
        <p className="mt-3 text-[13px] text-[var(--text-2)]">No copied trades yet.</p>
      ) : (
        <div className="mt-3 flex flex-col gap-2">
          {tradesPage.trades.map((trade) => (
            <div
              key={trade.id}
              className="flex items-center justify-between rounded-[10px] border border-[var(--border)] bg-[var(--surface)] p-3"
            >
              <div className="flex flex-col gap-0.5">
                <span className="font-mono text-[12.5px] text-[var(--text)]">
                  {trade.computedVolumeLots} lots
                </span>
                <span className="text-[11.5px] text-[var(--text-3)]">{trade.status}</span>
              </div>
              <span
                className={`font-mono text-[13px] font-semibold ${
                  trade.realizedPnl === null
                    ? "text-[var(--text-3)]"
                    : trade.realizedPnl >= 0
                      ? "text-[var(--pos)]"
                      : "text-[var(--neg)]"
                }`}
              >
                {trade.realizedPnl === null ? "—" : trade.realizedPnl.toFixed(2)}
              </span>
            </div>
          ))}
        </div>
      )}

      {tradesPage.total > tradesPage.pageSize && (
        <div className="mt-3 flex items-center gap-3">
          <button
            type="button"
            disabled={pending || tradesPage.page === 0}
            onClick={() => goToPage(tradesPage.page - 1)}
            className="text-[12.5px] font-medium text-[var(--text-2)] underline-offset-2 hover:underline disabled:opacity-40"
          >
            Previous
          </button>
          <span className="text-[12px] text-[var(--text-3)]">
            Page {tradesPage.page + 1} of {lastPage + 1}
          </span>
          <button
            type="button"
            disabled={pending || tradesPage.page >= lastPage}
            onClick={() => goToPage(tradesPage.page + 1)}
            className="text-[12.5px] font-medium text-[var(--text-2)] underline-offset-2 hover:underline disabled:opacity-40"
          >
            Next
          </button>
        </div>
      )}
    </div>
  );
}
