import { requireSession } from "@/lib/auth";
import { SampleLineChart } from "@/components/SampleLineChart";

const SAMPLE_EQUITY = [31200, 31800, 31400, 32600, 33100, 32800, 34500, 35200, 34900, 36800, 37600, 38200];

const SAMPLE_STATS = [
  { label: "Total equity", value: "$38,200" },
  { label: "Return · all time", value: "+21.6%" },
  { label: "Masters copied", value: "2" },
  { label: "Win rate", value: "61%" },
  { label: "Trades copied", value: "184" },
  { label: "Best month", value: "+8.4%" },
];

const SAMPLE_INSTRUMENTS = [
  { symbol: "XAUUSD", pnl: 2140 },
  { symbol: "EURUSD", pnl: 860 },
  { symbol: "US30", pnl: -320 },
  { symbol: "GBPUSD", pnl: 410 },
];

/**
 * TICKET-116 — mirrors Nectrix.dc.html's `FOLLOWER · ANALYTICS` (`vFollowerAnalytics`, `:1445-
 * 1481`). A genuine gap flagged during planning — no ticket names a Follower-side analytics
 * endpoint explicitly (TICKET-116's own real Master Analytics endpoint is ownership-scoped to the
 * owning Master, see MasterAnalyticsService's own Javadoc). Full placeholder using the mock's own
 * sample figures, honestly inert (no fetch calls to an endpoint that doesn't exist).
 */
export default async function FollowerAnalyticsPage() {
  await requireSession();
  const maxAbsPnl = Math.max(1, ...SAMPLE_INSTRUMENTS.map((i) => Math.abs(i.pnl)));

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">
          My analytics
        </h1>
        <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
          Your copied performance across all connected masters.
        </p>
        <p className="mt-2 text-[12.5px] text-[var(--text-3)]" title="Sample data — real follower-side analytics haven't shipped yet">
          Showing sample data — this view isn&apos;t wired up to your real activity yet.
        </p>
      </div>

      <div className="mb-4 grid grid-cols-[repeat(auto-fit,minmax(155px,1fr))] gap-3.5 opacity-70">
        {SAMPLE_STATS.map((s) => (
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

      <div className="mb-4 rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5 opacity-70">
        <div className="mb-4 text-[14px] font-semibold text-[var(--text)]">Equity growth</div>
        <div className="h-[180px]">
          <SampleLineChart values={SAMPLE_EQUITY} height={180} gradientId="follower-analytics-equity" />
        </div>
      </div>

      <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5 opacity-70">
        <div className="mb-4 text-[14px] font-semibold text-[var(--text)]">P&amp;L by instrument</div>
        <div className="flex flex-col gap-3.5">
          {SAMPLE_INSTRUMENTS.map((p) => (
            <div key={p.symbol}>
              <div className="mb-1.5 flex items-center justify-between text-[13px]">
                <span className="font-mono font-semibold text-[var(--text)]">{p.symbol}</span>
                <span
                  className={`font-mono font-semibold ${p.pnl >= 0 ? "text-[var(--pos)]" : "text-[var(--neg)]"}`}
                >
                  {p.pnl >= 0 ? "+" : ""}
                  {p.pnl.toLocaleString()}
                </span>
              </div>
              <div className="h-1.5 overflow-hidden rounded-full bg-[var(--surface-2)]">
                <div
                  className={`h-full rounded-full ${p.pnl >= 0 ? "bg-[var(--pos)]" : "bg-[var(--neg)]"}`}
                  style={{ width: `${(Math.abs(p.pnl) / maxAbsPnl) * 100}%` }}
                />
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
