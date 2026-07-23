import Link from "next/link";
import { listAllCopiedTrades } from "@nectrix/api-client";
import type { CopiedTradeStatus } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";
import { RefreshButton } from "./RefreshButton";
import { LiveRefresh } from "./LiveRefresh";

const STATUSES: CopiedTradeStatus[] = [
  "PENDING",
  "SUBMITTED",
  "FILLED",
  "PARTIALLY_CLOSED",
  "CLOSED",
  "REJECTED",
  "FAILED",
];
const PAGE_SIZE = 25;

function queryString(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== "") search.set(key, String(value));
  }
  const query = search.toString();
  return query ? `?${query}` : "";
}

/**
 * TICKET-116 — filterable trade history across every relationship the caller has, not folded into
 * any one mock screen (the mock's own "Recent copied trades"/"Latest copied trades" lists on the
 * dashboard are unfiltered previews of the same data) — new real functionality the ticket's own
 * scope calls for. GET-form filters, no client JS needed: submitting reloads this same route with
 * updated searchParams, which Next.js resolves server-side.
 */
export default async function TradeHistoryPage({
  searchParams,
}: {
  searchParams: Promise<{
    symbol?: string;
    from?: string;
    to?: string;
    status?: string;
    page?: string;
  }>;
}) {
  const { session, accessToken } = await requireSession();
  const params = await searchParams;
  const role = session.roles.includes("MASTER") ? "master" : "follower";
  const status = STATUSES.includes(params.status as CopiedTradeStatus)
    ? (params.status as CopiedTradeStatus)
    : undefined;
  const page = Math.max(0, Number(params.page) || 0);

  // <input type="date"> submits a plain YYYY-MM-DD — core-app's `from`/`to` query params bind to
  // java.time.Instant (Instant.parse), which needs a full ISO instant, not a bare date.
  const tradesPage = await listAllCopiedTrades(coreAppBaseUrl(), accessToken, {
    role,
    symbol: params.symbol || undefined,
    from: params.from ? `${params.from}T00:00:00Z` : undefined,
    to: params.to ? `${params.to}T23:59:59Z` : undefined,
    status,
    page,
    pageSize: PAGE_SIZE,
  });
  const lastPage = Math.max(0, Math.ceil(tradesPage.total / tradesPage.pageSize) - 1);
  const hasOpenTrades = tradesPage.trades.some(
    (t) => t.status === "FILLED" || t.status === "PARTIALLY_CLOSED",
  );

  return (
    <div>
      <LiveRefresh hasOpenTrades={hasOpenTrades} />
      <div className="mb-6 flex items-start justify-between gap-3">
        <div>
          <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">
            Trade history
          </h1>
          <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
            Every copied trade across{" "}
            {role === "master" ? "your followers" : "the masters you copy"}.
          </p>
        </div>
        <RefreshButton />
      </div>

      <form
        method="get"
        className="mb-4 flex flex-wrap items-end gap-3 rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-4"
      >
        <label className="flex flex-col gap-1.5">
          <span className="text-[11.5px] font-medium text-[var(--text-2)]">Symbol</span>
          <input
            name="symbol"
            defaultValue={params.symbol}
            placeholder="EURUSD"
            className="h-9 w-[130px] rounded-[9px] border border-[var(--border)] bg-transparent px-3 font-mono text-[12.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          />
        </label>
        <label className="flex flex-col gap-1.5">
          <span className="text-[11.5px] font-medium text-[var(--text-2)]">From</span>
          <input
            type="date"
            name="from"
            defaultValue={params.from?.slice(0, 10)}
            className="h-9 rounded-[9px] border border-[var(--border)] bg-transparent px-3 text-[12.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          />
        </label>
        <label className="flex flex-col gap-1.5">
          <span className="text-[11.5px] font-medium text-[var(--text-2)]">To</span>
          <input
            type="date"
            name="to"
            defaultValue={params.to?.slice(0, 10)}
            className="h-9 rounded-[9px] border border-[var(--border)] bg-transparent px-3 text-[12.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          />
        </label>
        <label className="flex flex-col gap-1.5">
          <span className="text-[11.5px] font-medium text-[var(--text-2)]">Status</span>
          <select
            name="status"
            defaultValue={status ?? ""}
            className="h-9 rounded-[9px] border border-[var(--border)] bg-transparent px-3 text-[12.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          >
            <option value="">Any</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </label>
        <button
          type="submit"
          className="h-9 rounded-[9px] bg-[var(--accent)] px-4 text-[12.5px] font-semibold text-white"
        >
          Apply filters
        </button>
        {(params.symbol || params.from || params.to || params.status) && (
          <Link
            href="/trade-history"
            className="text-[12.5px] font-medium text-[var(--text-2)] underline-offset-2 hover:underline"
          >
            Clear
          </Link>
        )}
      </form>

      <div className="overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)]">
        {tradesPage.trades.length === 0 ? (
          <p className="px-5 py-10 text-center text-[13px] text-[var(--text-2)]">
            No trades match these filters.
          </p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[640px] border-collapse">
              <thead>
                <tr>
                  <th className="whitespace-nowrap px-5 py-2.5 text-left text-[11.5px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
                    Symbol
                  </th>
                  <th className="whitespace-nowrap px-5 py-2.5 text-left text-[11.5px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
                    Status
                  </th>
                  <th className="whitespace-nowrap px-5 py-2.5 text-left text-[11.5px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
                    Type
                  </th>
                  <th className="whitespace-nowrap px-5 py-2.5 text-right text-[11.5px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
                    Volume
                  </th>
                  <th className="whitespace-nowrap px-5 py-2.5 text-right text-[11.5px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
                    P&amp;L
                  </th>
                  <th className="whitespace-nowrap px-5 py-2.5 text-right text-[11.5px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
                    Opened
                  </th>
                </tr>
              </thead>
              <tbody>
                {tradesPage.trades.map((trade) => {
                  // TICKET-124 — closing a position transitions the SAME cell from unrealized to
                  // the existing real realizedPnl value on the next fetch, with no separate
                  // "unrealized" UI state to blank out mid-transition.
                  const isClosed = trade.status === "CLOSED";
                  const pnl = isClosed ? trade.realizedPnl : trade.unrealizedPnl;
                  return (
                    <tr key={trade.id} className="border-t border-[var(--border)]">
                      <td className="px-5 py-3 font-mono text-[13px] font-semibold text-[var(--text)]">
                        {trade.canonicalSymbol}
                      </td>
                      <td className="px-5 py-3 text-[12.5px] text-[var(--text-2)]">
                        {trade.status}
                      </td>
                      <td
                        className={`px-5 py-3 text-[12.5px] font-semibold ${
                          trade.direction === "BUY" ? "text-[var(--pos)]" : "text-[var(--neg)]"
                        }`}
                      >
                        {trade.direction}
                      </td>
                      <td className="px-5 py-3 text-right font-mono text-[12.5px] text-[var(--text)]">
                        {trade.computedVolumeLots}
                      </td>
                      <td
                        className={`px-5 py-3 text-right font-mono text-[13px] font-semibold ${
                          pnl === null
                            ? "text-[var(--text-3)]"
                            : pnl >= 0
                              ? "text-[var(--pos)]"
                              : "text-[var(--neg)]"
                        }`}
                      >
                        {pnl === null ? "—" : pnl.toFixed(2)}
                        {!isClosed && pnl !== null && (
                          <span className="ml-1 text-[10px] font-normal text-[var(--text-3)]">
                            unrealized
                          </span>
                        )}
                      </td>
                      <td className="px-5 py-3 text-right text-[12px] text-[var(--text-3)]">
                        {trade.openedAt ? new Date(trade.openedAt).toLocaleString() : "—"}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {tradesPage.total > tradesPage.pageSize && (
        <div className="mt-4 flex items-center gap-3">
          <Link
            href={queryString({ ...params, page: page - 1 })}
            aria-disabled={page === 0}
            className={`text-[12.5px] font-medium underline-offset-2 hover:underline ${
              page === 0
                ? "pointer-events-none text-[var(--text-3)] opacity-40"
                : "text-[var(--text-2)]"
            }`}
          >
            Previous
          </Link>
          <span className="text-[12px] text-[var(--text-3)]">
            Page {page + 1} of {lastPage + 1}
          </span>
          <Link
            href={queryString({ ...params, page: page + 1 })}
            aria-disabled={page >= lastPage}
            className={`text-[12.5px] font-medium underline-offset-2 hover:underline ${
              page >= lastPage
                ? "pointer-events-none text-[var(--text-3)] opacity-40"
                : "text-[var(--text-2)]"
            }`}
          >
            Next
          </Link>
        </div>
      )}
    </div>
  );
}
