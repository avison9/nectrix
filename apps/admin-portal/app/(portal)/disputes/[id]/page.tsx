import { cookies } from "next/headers";
import Link from "next/link";
import { getFeeLedgerDetail } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { verifyAccessToken } from "@/lib/session";
import { ResolveDisputeForm } from "../ResolveDisputeForm";

/**
 * TICKET-117 — the full computation_detail breakdown rendered as a real line-item table (not a
 * JSON dump), plus the underlying copied_trades in that period window, plus the resolve form
 * (ADMIN only).
 */
export default async function DisputeDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const jar = await cookies();
  const token = jar.get("access_token")?.value;
  const session = token ? await verifyAccessToken(token) : null;
  const isAdmin = !!session?.roles.includes("ADMIN");
  const accessToken = jar.get("access_token")!.value;

  const { ledger, trades } = await getFeeLedgerDetail(coreAppBaseUrl(), accessToken, id);

  let detail: Record<string, unknown> = {};
  try {
    detail = JSON.parse(ledger.computationDetailJson) as Record<string, unknown>;
  } catch {
    // Malformed/empty JSON is real data, not something to hide behind a fallback — the raw
    // line-item table below just renders nothing extra beyond the ledger row's own columns.
  }

  const lineItems: Array<[string, unknown]> = Object.entries(detail);

  return (
    <div className="max-w-4xl">
      <Link
        href="/disputes"
        className="mb-4 inline-flex items-center gap-1.5 text-[13px] font-medium text-[var(--text-2)] hover:text-[var(--accent)]"
      >
        ← Back to disputes
      </Link>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-[23px] font-semibold tracking-tight text-[var(--text)]">
          Ledger {ledger.id}
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
      <p className="mt-1 text-[13.5px] text-[var(--text-2)]">
        {new Date(ledger.periodStart).toLocaleDateString()} –{" "}
        {new Date(ledger.periodEnd).toLocaleDateString()} · copy relationship{" "}
        <span className="font-mono">{ledger.copyRelationshipId}</span>
      </p>

      <h2 className="mt-6 text-[16px] font-semibold text-[var(--text)]">Computation</h2>
      <div className="mt-3 overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)]">
        <table className="w-full border-collapse text-[13px]">
          <tbody>
            <tr className="border-b border-[var(--border)]">
              <td className="px-5 py-2.5 text-[var(--text-2)]">Starting HWM</td>
              <td className="px-5 py-2.5 text-right font-mono text-[var(--text)]">
                ${ledger.startingHwm.toFixed(2)}
              </td>
            </tr>
            <tr className="border-b border-[var(--border)]">
              <td className="px-5 py-2.5 text-[var(--text-2)]">Ending equity</td>
              <td className="px-5 py-2.5 text-right font-mono text-[var(--text)]">
                ${ledger.endingEquity.toFixed(2)}
              </td>
            </tr>
            <tr className="border-b border-[var(--border)]">
              <td className="px-5 py-2.5 text-[var(--text-2)]">New profit above HWM</td>
              <td className="px-5 py-2.5 text-right font-mono text-[var(--text)]">
                ${ledger.newProfitAboveHwm.toFixed(2)}
              </td>
            </tr>
            <tr className="border-b border-[var(--border)]">
              <td className="px-5 py-2.5 text-[var(--text-2)]">Master fee</td>
              <td className="px-5 py-2.5 text-right font-mono text-[var(--text)]">
                ${ledger.masterFeeAmount.toFixed(2)}
              </td>
            </tr>
            <tr className="border-b border-[var(--border)]">
              <td className="px-5 py-2.5 text-[var(--text-2)]">Platform take</td>
              <td className="px-5 py-2.5 text-right font-mono text-[var(--text)]">
                ${ledger.platformTakeAmount.toFixed(2)}
              </td>
            </tr>
            <tr className="border-b border-[var(--border)] last:border-0">
              <td className="px-5 py-2.5 font-semibold text-[var(--text)]">Net to master</td>
              <td className="px-5 py-2.5 text-right font-mono font-semibold text-[var(--text)]">
                ${ledger.netToMasterAmount.toFixed(2)}
              </td>
            </tr>
            {lineItems
              .filter(([key]) => !["starting_hwm", "ending_equity", "new_profit_above_hwm", "master_fee_amount", "platform_take_amount", "net_to_master_amount"].includes(key))
              .map(([key, value]) => (
                <tr key={key} className="border-b border-[var(--border)] last:border-0">
                  <td className="px-5 py-2.5 text-[var(--text-2)]">{key}</td>
                  <td className="px-5 py-2.5 text-right font-mono text-[var(--text)]">
                    {String(value)}
                  </td>
                </tr>
              ))}
          </tbody>
        </table>
      </div>

      <h2 className="mt-6 text-[16px] font-semibold text-[var(--text)]">
        Underlying trades ({trades.length})
      </h2>
      <div className="mt-3 overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)]">
        <table className="w-full border-collapse text-[13px]">
          <thead>
            <tr className="border-b border-[var(--border)] text-left">
              {["Symbol", "Direction", "Volume", "Status", "Realized PnL", "Opened"].map(
                (heading) => (
                  <th
                    key={heading}
                    className="px-5 py-2.5 text-[11.5px] font-semibold tracking-wide text-[var(--text-3)] uppercase"
                  >
                    {heading}
                  </th>
                ),
              )}
            </tr>
          </thead>
          <tbody>
            {trades.length === 0 && (
              <tr>
                <td colSpan={6} className="px-5 py-8 text-center text-[var(--text-3)]">
                  No trades in this period.
                </td>
              </tr>
            )}
            {trades.map((trade) => (
              <tr key={trade.id} className="border-b border-[var(--border)] last:border-0">
                <td className="px-5 py-2.5 text-[var(--text)]">{trade.canonicalSymbol}</td>
                <td className="px-5 py-2.5 text-[var(--text-2)]">{trade.direction}</td>
                <td className="px-5 py-2.5 font-mono text-[var(--text-2)]">
                  {trade.computedVolumeLots}
                </td>
                <td className="px-5 py-2.5 text-[var(--text-2)]">{trade.status}</td>
                <td
                  className={`px-5 py-2.5 font-mono ${
                    trade.realizedPnl == null
                      ? "text-[var(--text-3)]"
                      : trade.realizedPnl >= 0
                        ? "text-[var(--pos)]"
                        : "text-[var(--neg)]"
                  }`}
                >
                  {trade.realizedPnl == null ? "—" : `$${trade.realizedPnl.toFixed(2)}`}
                </td>
                <td className="px-5 py-2.5 text-[var(--text-2)]">
                  {trade.openedAt ? new Date(trade.openedAt).toLocaleString() : "—"}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {isAdmin && ledger.status === "DISPUTED" && (
        <div className="mt-6">
          <ResolveDisputeForm ledgerId={ledger.id} />
        </div>
      )}
      {ledger.status !== "DISPUTED" && (
        <div className="mt-6 rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5 text-[13.5px] text-[var(--text-2)]">
          This dispute has been resolved — current status is{" "}
          <span className="font-semibold text-[var(--text)]">{ledger.status}</span>.
        </div>
      )}
    </div>
  );
}
