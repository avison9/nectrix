import Link from "next/link";
import { getMasterAnalytics, getMyMasterProfiles, listAllCopiedTrades } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";

function monthLabel(period: string): string {
  const date = new Date(`${period}-01T00:00:00Z`);
  return date.toLocaleDateString("en-US", { month: "long", year: "numeric", timeZone: "UTC" });
}

function monthDateRange(period: string): { from: string; to: string } {
  const [year, month] = period.split("-").map(Number);
  const lastDay = new Date(Date.UTC(year, month, 0)).getUTCDate();
  return {
    from: `${period}-01T00:00:00Z`,
    to: `${period}-${String(lastDay).padStart(2, "0")}T23:59:59Z`,
  };
}

/**
 * TICKET-116 — mirrors Nectrix.dc.html's `MASTER · MONTH DETAIL` (`vMasterMonthView`, `:1210-
 * 1239`), the drill-down from Master Analytics' own monthly-returns chart. Hybrid, not a full
 * placeholder: the month's return % comes from the real `getMasterAnalytics` payload, and its trade
 * list is a real, date-filtered call into the new cross-relationship trade-history endpoint
 * (TICKET-116's own `GET /copy-relationships/trades`) — nothing here is sample data.
 */
export default async function MasterMonthDetailPage({
  params,
}: {
  params: Promise<{ period: string }>;
}) {
  const { session, accessToken } = await requireSession();
  const { period } = await params;

  if (!session.roles.includes("MASTER")) {
    return (
      <div className="mx-auto max-w-[480px] py-16 text-center">
        <h1 className="text-[20px] font-semibold text-[var(--text)]">Month detail</h1>
        <p className="mt-2 text-[13.5px] text-[var(--text-2)]">
          This page is only available to Master accounts.
        </p>
      </div>
    );
  }

  const baseUrl = coreAppBaseUrl();
  // TICKET-125 — see analytics/page.tsx's own comment: shows the first profile until
  // per-strategy analytics selection is built.
  const profiles = await getMyMasterProfiles(baseUrl, accessToken);
  if (profiles.length === 0) {
    return (
      <div className="mx-auto max-w-[480px] py-16 text-center">
        <h1 className="text-[20px] font-semibold text-[var(--text)]">Month detail</h1>
        <p className="mt-2 text-[13.5px] text-[var(--text-2)]">
          You don&apos;t have a Master profile yet.
        </p>
      </div>
    );
  }
  const profileId = profiles[0].id;

  const [analytics, { from, to }] = [
    await getMasterAnalytics(baseUrl, accessToken, profileId, "ALL"),
    monthDateRange(period),
  ];
  const monthReturn = analytics.monthlyReturns.find((m) => m.month === period);

  const tradesPage = await listAllCopiedTrades(baseUrl, accessToken, {
    role: "master",
    from,
    to,
    status: "CLOSED",
    pageSize: 50,
  });

  return (
    <div className="mx-auto max-w-[900px]">
      <Link
        href="/analytics"
        className="mb-4.5 inline-flex items-center gap-1.5 rounded-[9px] border border-[var(--border)] px-3 py-1.5 text-[13px] font-semibold text-[var(--text-2)] hover:bg-[var(--surface-2)]"
      >
        ← Back to analytics
      </Link>

      <div className="mb-5.5 flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">
            {monthLabel(period)}
          </h1>
          <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
            {tradesPage.total} closed {tradesPage.total === 1 ? "trade" : "trades"} this month
          </p>
        </div>
        <div className="text-right">
          <div className="text-[12px] text-[var(--text-3)]">Return</div>
          <div
            className={`mt-0.5 font-mono text-[22px] font-semibold ${
              monthReturn === undefined
                ? "text-[var(--text-3)]"
                : monthReturn.returnPct >= 0
                  ? "text-[var(--pos)]"
                  : "text-[var(--neg)]"
            }`}
          >
            {monthReturn === undefined
              ? "—"
              : `${monthReturn.returnPct >= 0 ? "+" : ""}${monthReturn.returnPct.toFixed(2)}%`}
          </div>
        </div>
      </div>

      <div className="overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)]">
        <div className="border-b border-[var(--border)] px-5 py-3.5 text-[14px] font-semibold text-[var(--text)]">
          Trades this month
        </div>
        {tradesPage.trades.length === 0 ? (
          <p className="px-5 py-10 text-center text-[13px] text-[var(--text-2)]">
            No closed trades in this month.
          </p>
        ) : (
          <ul className="flex flex-col">
            {tradesPage.trades.map((trade) => (
              <li
                key={trade.id}
                className="flex items-center gap-3 border-t border-[var(--border)] px-5 py-3 first:border-t-0"
              >
                <div className="min-w-0 flex-1">
                  <div className="font-mono text-[13.5px] font-semibold text-[var(--text)]">
                    {trade.canonicalSymbol}
                  </div>
                  <div className="text-[11.5px] text-[var(--text-3)]">
                    {trade.computedVolumeLots} lots
                  </div>
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
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
