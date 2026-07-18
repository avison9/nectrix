import Link from "next/link";
import { requireSession } from "@/lib/auth";

const SAMPLE_TRADES = [
  { side: "BUY", symbol: "XAUUSD", lots: "0.50", pnl: 214, time: "2h ago" },
  { side: "SELL", symbol: "EURUSD", lots: "1.00", pnl: -38, time: "5h ago" },
  { side: "BUY", symbol: "US30", lots: "0.20", pnl: 96, time: "1d ago" },
  { side: "BUY", symbol: "GBPUSD", lots: "0.75", pnl: 61, time: "1d ago" },
  { side: "SELL", symbol: "XAUUSD", lots: "0.50", pnl: 172, time: "2d ago" },
];

/**
 * TICKET-116 — mirrors Nectrix.dc.html's `MASTER · FOLLOWER DETAIL` (`vMasterFollowerView`, `:1155-
 * 1208`). A genuine backend gap, not just an unbuilt UI: `CopyRelationshipService.getCopyRelationship`
 * is follower-facing-only ownership (`@PostAuthorize` checks `followerUserId`, see that class's own
 * Javadoc) — a Master calling it for one of their own followers' relationships gets a 403, the same
 * "Admin-Portal capability list territory, defers to a later ticket" gap this session's research
 * already flagged. Full placeholder using the mock's own sample figures, honestly inert.
 */
export default async function MasterFollowerDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  await requireSession();
  const { id } = await params;

  return (
    <div className="mx-auto max-w-[900px]">
      <Link
        href="/dashboard"
        className="mb-4.5 inline-flex items-center gap-1.5 rounded-[9px] border border-[var(--border)] px-3 py-1.5 text-[13px] font-semibold text-[var(--text-2)] hover:bg-[var(--surface-2)]"
      >
        ← Back to dashboard
      </Link>

      <div className="mb-5.5 flex flex-wrap items-center gap-3.5">
        <div className="flex h-[54px] w-[54px] shrink-0 items-center justify-center rounded-full bg-[var(--accent-2)] text-[18px] font-semibold text-[var(--accent)]">
          {id.slice(0, 2).toUpperCase()}
        </div>
        <div className="min-w-0 flex-1">
          <h1 className="text-[23px] font-semibold tracking-tight text-[var(--text)]">
            Follower {id.slice(0, 8)}…
          </h1>
          <div className="mt-0.5 font-mono text-[13px] text-[var(--text-3)]">
            Sample account · Sample Broker
          </div>
        </div>
      </div>

      <p
        className="mb-4 text-[12.5px] text-[var(--text-3)]"
        title="Per-follower detail views for Masters aren't available yet"
      >
        Showing sample data — this view isn&apos;t wired up to real follower activity yet.
      </p>

      <div className="mb-4 grid grid-cols-[repeat(auto-fit,minmax(150px,1fr))] gap-3.5 opacity-70">
        {[
          { label: "Equity", value: "$12,400" },
          { label: "P&L · 30d", value: "+$860" },
          { label: "Copy ratio", value: "1.0×" },
          { label: "Broker", value: "Sample Broker" },
        ].map((s) => (
          <div
            key={s.label}
            className="rounded-[14px] border border-[var(--border)] bg-[var(--surface)] p-4"
          >
            <div className="text-[12px] font-medium text-[var(--text-2)]">{s.label}</div>
            <div className="mt-1.5 font-mono text-[18px] font-semibold tracking-tight text-[var(--text)]">
              {s.value}
            </div>
          </div>
        ))}
      </div>

      <div className="mb-4 rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5 opacity-70">
        <div className="mb-3.5 text-[14px] font-semibold text-[var(--text)]">
          Copied equity · 30d
        </div>
        <p className="flex h-[160px] items-center justify-center text-[13px] text-[var(--text-2)]">
          Equity chart not available yet.
        </p>
      </div>

      <div className="overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)] opacity-70">
        <div className="border-b border-[var(--border)] px-5 py-3.5 text-[14px] font-semibold text-[var(--text)]">
          Recent copied trades
        </div>
        <ul className="flex flex-col">
          {SAMPLE_TRADES.map((t, i) => (
            <li
              key={i}
              className="flex items-center gap-3 border-t border-[var(--border)] px-5 py-3 first:border-t-0"
            >
              <span
                className={`shrink-0 rounded-full px-2 py-0.5 text-[10.5px] font-semibold ${
                  t.side === "BUY"
                    ? "bg-[var(--pos)]/15 text-[var(--pos)]"
                    : "bg-[var(--neg)]/15 text-[var(--neg)]"
                }`}
              >
                {t.side}
              </span>
              <div className="min-w-0 flex-1">
                <div className="font-mono text-[13.5px] font-semibold text-[var(--text)]">
                  {t.symbol}
                </div>
                <div className="text-[11.5px] text-[var(--text-3)]">{t.lots} lots</div>
              </div>
              <div className="text-right">
                <div
                  className={`font-mono text-[13px] font-semibold ${t.pnl >= 0 ? "text-[var(--pos)]" : "text-[var(--neg)]"}`}
                >
                  {t.pnl >= 0 ? "+" : ""}
                  {t.pnl}
                </div>
                <div className="text-[11px] text-[var(--text-3)]">{t.time}</div>
              </div>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
