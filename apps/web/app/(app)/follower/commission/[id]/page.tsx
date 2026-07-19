import Link from "next/link";
import { getMySettlementDetail } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";
import { RaiseDisputeButton } from "../RaiseDisputeButton";

/**
 * TICKET-117 follow-up — a Follower's own settlement detail: the full computation breakdown (not
 * a JSON dump — same real line-item rendering AdminController's own dispute detail already
 * established) plus the underlying copied_trades, plus a real "Raise a dispute" action if this
 * row isn't already DISPUTED/VOID. Ownership is enforced server-side (FeeLedgerService) — a
 * settlement that isn't this caller's own 404s, it never silently 403s and hints it exists.
 */
export default async function FollowerSettlementDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const { accessToken } = await requireSession();
  const { ledger, trades } = await getMySettlementDetail(coreAppBaseUrl(), accessToken, id);

  const canDispute = ledger.status !== "DISPUTED" && ledger.status !== "VOID";

  return (
    <div className="mx-auto max-w-[760px]">
      <Link
        href="/follower/commission"
        className="mb-4 inline-flex items-center gap-1.5 text-[13px] font-medium text-[var(--text-2)] hover:text-[var(--accent)]"
      >
        ← Back to commission &amp; fees
      </Link>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-[22px] font-semibold tracking-tight text-[var(--text)]">
          Settlement —{" "}
          {new Date(ledger.periodStart).toLocaleDateString(undefined, {
            month: "long",
            year: "numeric",
          })}
        </h1>
        <span
          className={`rounded-full px-2.5 py-1 text-[12px] font-semibold ${
            ledger.status === "DISPUTED"
              ? "bg-[var(--neg)]/15 text-[var(--neg)]"
              : ledger.status === "VOID"
                ? "bg-[var(--surface-2)] text-[var(--text-3)]"
                : "bg-[var(--pos)]/15 text-[var(--pos)]"
          }`}
        >
          {ledger.status}
        </span>
      </div>

      <div className="mt-5 overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)]">
        <table className="w-full border-collapse text-[13px]">
          <tbody>
            {[
              ["Starting HWM", ledger.startingHwm],
              ["Ending equity", ledger.endingEquity],
              ["New profit above HWM", ledger.newProfitAboveHwm],
              ["Master fee", ledger.masterFeeAmount],
              ["Platform take", ledger.platformTakeAmount],
            ].map(([label, value]) => (
              <tr key={label as string} className="border-b border-[var(--border)]">
                <td className="px-5 py-2.5 text-[var(--text-2)]">{label}</td>
                <td className="px-5 py-2.5 text-right font-mono text-[var(--text)]">
                  ${(value as number).toFixed(2)}
                </td>
              </tr>
            ))}
            <tr className="last:border-0">
              <td className="px-5 py-2.5 font-semibold text-[var(--text)]">Net to master</td>
              <td className="px-5 py-2.5 text-right font-mono font-semibold text-[var(--text)]">
                ${ledger.netToMasterAmount.toFixed(2)}
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <h2 className="mt-6 text-[15px] font-semibold text-[var(--text)]">
        Trades in this period ({trades.length})
      </h2>
      <div className="mt-3 overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)]">
        <table className="w-full border-collapse text-[13px]">
          <thead>
            <tr className="border-b border-[var(--border)] text-left">
              {["Symbol", "Direction", "Volume", "Realized PnL"].map((h) => (
                <th
                  key={h}
                  className="px-5 py-2.5 text-[11px] font-semibold tracking-wide text-[var(--text-3)] uppercase"
                >
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {trades.length === 0 && (
              <tr>
                <td colSpan={4} className="px-5 py-6 text-center text-[var(--text-3)]">
                  No trades in this period.
                </td>
              </tr>
            )}
            {trades.map((t) => (
              <tr key={t.id} className="border-b border-[var(--border)] last:border-0">
                <td className="px-5 py-2.5 text-[var(--text)]">{t.canonicalSymbol}</td>
                <td className="px-5 py-2.5 text-[var(--text-2)]">{t.direction}</td>
                <td className="px-5 py-2.5 font-mono text-[var(--text-2)]">
                  {t.computedVolumeLots}
                </td>
                <td
                  className={`px-5 py-2.5 font-mono ${
                    t.realizedPnl == null
                      ? "text-[var(--text-3)]"
                      : t.realizedPnl >= 0
                        ? "text-[var(--pos)]"
                        : "text-[var(--neg)]"
                  }`}
                >
                  {t.realizedPnl == null ? "—" : `$${t.realizedPnl.toFixed(2)}`}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {canDispute && (
        <div className="mt-6">
          <RaiseDisputeButton ledgerId={ledger.id} />
        </div>
      )}
    </div>
  );
}
