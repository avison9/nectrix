import Link from "next/link";
import { ApiError, getMasterAnalytics, getMyMasterProfile } from "@nectrix/api-client";
import type { AnalyticsPeriod } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";
import { EquityCurveChart, MonthlyReturnsChart } from "./AnalyticsCharts";

const PERIODS: AnalyticsPeriod[] = ["7D", "30D", "90D", "YTD", "ALL"];

function queryString(period: string): string {
  return `?period=${period}`;
}

/**
 * TICKET-116 — mirrors Nectrix.dc.html's `MASTER · ANALYTICS` (`vMasterAnalytics`, `:548-617`).
 * Master-only (Sidebar routes non-Masters to `/follower/analytics` instead); the actual enforcement
 * is server-side — `MasterAnalyticsService`'s own `@PostAuthorize` rejects anyone but the owning
 * Master (or staff), this page's own role check is just the UX-level redirect-to-explanation.
 */
export default async function MasterAnalyticsPage({
  searchParams,
}: {
  searchParams: Promise<{ period?: string }>;
}) {
  const { session, accessToken } = await requireSession();
  const params = await searchParams;
  const period = (PERIODS.includes(params.period as AnalyticsPeriod) ? params.period : "30D") as AnalyticsPeriod;

  if (!session.roles.includes("MASTER")) {
    return (
      <div className="mx-auto max-w-[480px] py-16 text-center">
        <h1 className="text-[20px] font-semibold text-[var(--text)]">Master Analytics</h1>
        <p className="mt-2 text-[13.5px] text-[var(--text-2)]">
          This page is only available to Master accounts.
        </p>
      </div>
    );
  }

  const baseUrl = coreAppBaseUrl();
  let profileId: string;
  try {
    profileId = (await getMyMasterProfile(baseUrl, accessToken)).id;
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      return (
        <div className="mx-auto max-w-[480px] py-16 text-center">
          <h1 className="text-[20px] font-semibold text-[var(--text)]">Master Analytics</h1>
          <p className="mt-2 text-[13.5px] text-[var(--text-2)]">
            You don&apos;t have a Master profile yet.
          </p>
          <Link
            href="/master-profile"
            className="mt-4 inline-block rounded-[10px] bg-[var(--accent)] px-4 py-2 text-[13px] font-semibold text-white"
          >
            Become a Master
          </Link>
        </div>
      );
    }
    throw error;
  }

  const analytics = await getMasterAnalytics(baseUrl, accessToken, profileId, period);
  const { equityCurve, monthlyReturns, pnlByInstrument } = analytics;

  const startEquity = equityCurve[0]?.equity ?? 0;
  const endEquity = equityCurve[equityCurve.length - 1]?.equity ?? 0;
  const periodReturnPct = startEquity !== 0 ? ((endEquity - startEquity) / startEquity) * 100 : 0;
  const bestMonth = monthlyReturns.reduce(
    (best, m) => (best === null || m.returnPct > best.returnPct ? m : best),
    null as (typeof monthlyReturns)[number] | null,
  );
  const worstMonth = monthlyReturns.reduce(
    (worst, m) => (worst === null || m.returnPct < worst.returnPct ? m : worst),
    null as (typeof monthlyReturns)[number] | null,
  );
  const totalInstrumentPnl = pnlByInstrument.reduce((sum, p) => sum + p.totalPnl, 0);

  const stats = [
    { label: "Current equity", value: `$${endEquity.toLocaleString(undefined, { maximumFractionDigits: 2 })}` },
    { label: `Return · ${period}`, value: `${periodReturnPct >= 0 ? "+" : ""}${periodReturnPct.toFixed(2)}%` },
    { label: "Best month", value: bestMonth ? `${bestMonth.month} (+${bestMonth.returnPct.toFixed(1)}%)` : "—" },
    { label: "Worst month", value: worstMonth ? `${worstMonth.month} (${worstMonth.returnPct.toFixed(1)}%)` : "—" },
    { label: "Instruments traded", value: String(pnlByInstrument.length) },
    { label: "Total closed P&L", value: `$${totalInstrumentPnl.toLocaleString(undefined, { maximumFractionDigits: 2 })}` },
  ];

  const maxAbsPnl = Math.max(1, ...pnlByInstrument.map((p) => Math.abs(p.totalPnl)));

  return (
    <div>
      <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">Analytics</h1>
          <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
            Deep performance view for your master strategy.
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
        <div className="mb-4 text-[14px] font-semibold text-[var(--text)]">Cumulative growth</div>
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
