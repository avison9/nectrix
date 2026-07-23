import Link from "next/link";
import { getFollowerAnalytics, listCopyRelationships } from "@nectrix/api-client";
import type { AnalyticsPeriod } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";
import { EquityCurveChart, MonthlyReturnsChart } from "../../analytics/AnalyticsCharts";

const PERIODS: AnalyticsPeriod[] = ["7D", "30D", "90D", "YTD", "ALL"];

function queryString(period: string): string {
  return `?period=${period}`;
}

/**
 * Feature — this used to be 100% hardcoded sample data (Nectrix.dc.html's own mock figures),
 * explicitly labeled as never wired to any real endpoint. Real now: FollowerAnalyticsController
 * aggregates equity/closed-P&L across every broker account this Follower has ever copied onto
 * (see that service's own Javadoc for why it's an aggregation, unlike Master's single-account
 * view) — same EquityCurveChart/MonthlyReturnsChart components Master's own page.tsx uses, since
 * the response shape is identical (FollowerAnalytics = MasterAnalytics).
 */
export default async function FollowerAnalyticsPage({
  searchParams,
}: {
  searchParams: Promise<{ period?: string }>;
}) {
  const { accessToken } = await requireSession();
  const params = await searchParams;
  const period = (PERIODS.includes(params.period as AnalyticsPeriod) ? params.period : "30D") as AnalyticsPeriod;
  const baseUrl = coreAppBaseUrl();

  const [analytics, relationships] = await Promise.all([
    getFollowerAnalytics(baseUrl, accessToken, period),
    listCopyRelationships(baseUrl, accessToken, { role: "follower", status: "ACTIVE" }),
  ]);
  const { equityCurve, monthlyReturns, pnlByInstrument } = analytics;

  const startEquity = equityCurve[0]?.equity ?? 0;
  const endEquity = equityCurve[equityCurve.length - 1]?.equity ?? 0;
  const periodReturnPct = startEquity !== 0 ? ((endEquity - startEquity) / startEquity) * 100 : 0;
  const bestMonth = monthlyReturns.reduce(
    (best, m) => (best === null || m.returnPct > best.returnPct ? m : best),
    null as (typeof monthlyReturns)[number] | null,
  );
  const totalInstrumentPnl = pnlByInstrument.reduce((sum, p) => sum + p.totalPnl, 0);
  const totalTradesCopied = pnlByInstrument.reduce((sum, p) => sum + p.tradeCount, 0);
  const mastersCopied = new Set(relationships.map((r) => r.masterProfileId)).size;

  const stats = [
    { label: "Current equity", value: `$${endEquity.toLocaleString(undefined, { maximumFractionDigits: 2 })}` },
    { label: `Return · ${period}`, value: `${periodReturnPct >= 0 ? "+" : ""}${periodReturnPct.toFixed(2)}%` },
    { label: "Masters copied", value: String(mastersCopied) },
    { label: "Trades copied", value: String(totalTradesCopied) },
    { label: "Best month", value: bestMonth ? `${bestMonth.month} (+${bestMonth.returnPct.toFixed(1)}%)` : "—" },
    { label: "Total closed P&L", value: `$${totalInstrumentPnl.toLocaleString(undefined, { maximumFractionDigits: 2 })}` },
  ];

  const maxAbsPnl = Math.max(1, ...pnlByInstrument.map((p) => Math.abs(p.totalPnl)));

  return (
    <div>
      <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">
            My analytics
          </h1>
          <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
            Your copied performance across all connected masters.
          </p>
        </div>
        <div className="flex gap-0.5 rounded-[10px] bg-[var(--surface-2)] p-[3px]">
          {PERIODS.map((p) => (
            <Link
              key={p}
              href={queryString(p)}
              className={`rounded-[7px] px-3 py-1.5 text-[12.5px] font-medium ${
                p === period ? "bg-[var(--surface)] text-[var(--text)]" : "text-[var(--text-2)]"
              }`}
            >
              {p}
            </Link>
          ))}
        </div>
      </div>

      <div className="mb-4 grid grid-cols-[repeat(auto-fit,minmax(155px,1fr))] gap-3.5">
        {stats.map((s) => (
          <div
            key={s.label}
            className="rounded-[14px] border border-[var(--border)] bg-[var(--surface)] p-4"
          >
            <div className="text-[12px] font-medium text-[var(--text-2)]">{s.label}</div>
            <div className="mt-1.5 font-mono text-[20px] font-semibold tracking-tight text-[var(--text)]">
              {s.value}
            </div>
          </div>
        ))}
      </div>

      <div className="mb-4 rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5">
        <div className="mb-4 text-[14px] font-semibold text-[var(--text)]">Equity growth</div>
        <EquityCurveChart data={equityCurve} />
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5">
          <div className="mb-1 text-[14px] font-semibold text-[var(--text)]">Monthly returns</div>
          <div className="mb-4 text-[11.5px] text-[var(--text-3)]">Month-over-month equity change</div>
          <MonthlyReturnsChart data={monthlyReturns} />
        </div>

        <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5">
          <div className="mb-4 text-[14px] font-semibold text-[var(--text)]">P&amp;L by instrument</div>
          {pnlByInstrument.length === 0 ? (
            <p className="text-[13px] text-[var(--text-2)]">No closed trades in this period yet.</p>
          ) : (
            <div className="flex flex-col gap-3.5">
              {pnlByInstrument.map((p) => (
                <div key={p.canonicalSymbol}>
                  <div className="mb-1.5 flex items-center justify-between text-[13px]">
                    <span className="font-mono font-semibold text-[var(--text)]">
                      {p.canonicalSymbol}
                    </span>
                    <span
                      className={`font-mono font-semibold ${p.totalPnl >= 0 ? "text-[var(--pos)]" : "text-[var(--neg)]"}`}
                    >
                      {p.totalPnl >= 0 ? "+" : ""}
                      {p.totalPnl.toLocaleString(undefined, { maximumFractionDigits: 2 })}
                    </span>
                  </div>
                  <div className="h-1.5 overflow-hidden rounded-full bg-[var(--surface-2)]">
                    <div
                      className={`h-full rounded-full ${p.totalPnl >= 0 ? "bg-[var(--pos)]" : "bg-[var(--neg)]"}`}
                      style={{ width: `${(Math.abs(p.totalPnl) / maxAbsPnl) * 100}%` }}
                    />
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
