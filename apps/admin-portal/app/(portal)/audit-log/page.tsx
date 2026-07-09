import { cookies } from "next/headers";
import Link from "next/link";
import { listAuditLog } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";

const PAGE_SIZE = 25;

interface SearchParams {
  actorUserId?: string;
  targetType?: string;
  targetId?: string;
  from?: string;
  to?: string;
  page?: string;
}

function buildQuery(params: SearchParams, overrides: Partial<SearchParams>): string {
  const merged = { ...params, ...overrides };
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(merged)) {
    if (value) {
      search.set(key, value);
    }
  }
  return search.toString();
}

export default async function AuditLogPage({
  searchParams,
}: {
  searchParams: Promise<SearchParams>;
}) {
  const params = await searchParams;
  const page = params.page ? Math.max(0, Number(params.page)) : 0;

  const jar = await cookies();
  // middleware.ts + (portal)/layout.tsx already guarantee a valid ADMIN/
  // SUPPORT/MASTER session reaches this far.
  const accessToken = jar.get("access_token")!.value;

  const data = await listAuditLog(coreAppBaseUrl(), accessToken, {
    actorUserId: params.actorUserId,
    targetType: params.targetType,
    targetId: params.targetId,
    from: params.from,
    to: params.to,
    page,
    pageSize: PAGE_SIZE,
  });

  const totalPages = Math.max(1, Math.ceil(data.total / PAGE_SIZE));
  const rangeStart = data.total === 0 ? 0 : page * PAGE_SIZE + 1;
  const rangeEnd = Math.min(data.total, (page + 1) * PAGE_SIZE);

  return (
    <div>
      <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">Audit Log</h1>
      <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
        Every actioned change to platform state — impersonation, ledger adjustments, account
        provisioning, and more — read-only, filterable by actor/target/date.
      </p>

      <form method="GET" className="mt-6 flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1.5">
          <span className="text-[12.5px] font-medium text-[var(--text-2)]">Actor user ID</span>
          <input
            name="actorUserId"
            defaultValue={params.actorUserId}
            className="h-9 w-[220px] rounded-[10px] border border-[var(--border)] bg-transparent px-3 font-mono text-[12.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          />
        </label>
        <label className="flex flex-col gap-1.5">
          <span className="text-[12.5px] font-medium text-[var(--text-2)]">Target type</span>
          <input
            name="targetType"
            defaultValue={params.targetType}
            placeholder="USER"
            className="h-9 w-[140px] rounded-[10px] border border-[var(--border)] bg-transparent px-3 text-[12.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          />
        </label>
        <label className="flex flex-col gap-1.5">
          <span className="text-[12.5px] font-medium text-[var(--text-2)]">Target ID</span>
          <input
            name="targetId"
            defaultValue={params.targetId}
            className="h-9 w-[220px] rounded-[10px] border border-[var(--border)] bg-transparent px-3 font-mono text-[12.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          />
        </label>
        <label className="flex flex-col gap-1.5">
          <span className="text-[12.5px] font-medium text-[var(--text-2)]">From</span>
          <input
            type="datetime-local"
            name="from"
            defaultValue={params.from}
            className="h-9 rounded-[10px] border border-[var(--border)] bg-transparent px-3 text-[12.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          />
        </label>
        <label className="flex flex-col gap-1.5">
          <span className="text-[12.5px] font-medium text-[var(--text-2)]">To</span>
          <input
            type="datetime-local"
            name="to"
            defaultValue={params.to}
            className="h-9 rounded-[10px] border border-[var(--border)] bg-transparent px-3 text-[12.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          />
        </label>
        <button
          type="submit"
          className="h-9 rounded-[10px] bg-[var(--accent)] px-4 text-[12.5px] font-semibold text-white"
        >
          Filter
        </button>
        {(params.actorUserId || params.targetType || params.targetId || params.from || params.to) && (
          <Link
            href="/audit-log"
            className="text-[12.5px] font-medium text-[var(--text-2)] underline-offset-2 hover:underline"
          >
            Clear filters
          </Link>
        )}
      </form>

      <div className="mt-6 overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)]">
        <table className="w-full border-collapse text-[13px]">
          <thead>
            <tr className="border-b border-[var(--border)] text-left">
              {["Time", "Actor", "Action", "Target", "Metadata"].map((heading) => (
                <th
                  key={heading}
                  className="px-5 py-2.5 text-[11.5px] font-semibold tracking-wide text-[var(--text-3)] uppercase"
                >
                  {heading}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {data.entries.length === 0 && (
              <tr>
                <td colSpan={5} className="px-5 py-8 text-center text-[var(--text-3)]">
                  No audit log entries match these filters.
                </td>
              </tr>
            )}
            {data.entries.map((entry) => (
              <tr key={entry.id} className="border-b border-[var(--border)] last:border-0">
                <td className="px-5 py-2.5 whitespace-nowrap text-[var(--text-2)]">
                  {new Date(entry.createdAt).toLocaleString()}
                </td>
                <td className="px-5 py-2.5 font-mono text-[12px] text-[var(--text-2)]">
                  {entry.actorUserId ?? "—"}
                  <span className="ml-1.5 text-[var(--text-3)]">({entry.actorType})</span>
                </td>
                <td className="px-5 py-2.5 font-medium text-[var(--text)]">{entry.action}</td>
                <td className="px-5 py-2.5 font-mono text-[12px] text-[var(--text-2)]">
                  {entry.targetType ? `${entry.targetType}:${entry.targetId ?? "—"}` : "—"}
                </td>
                <td className="max-w-[280px] truncate px-5 py-2.5 font-mono text-[12px] text-[var(--text-3)]">
                  {entry.metadataJson ?? "—"}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="mt-3 flex items-center justify-between">
        <span className="text-[12.5px] text-[var(--text-3)]">
          {data.total === 0 ? "0 results" : `${rangeStart}–${rangeEnd} of ${data.total}`}
        </span>
        <div className="flex gap-2">
          <Link
            href={`/audit-log?${buildQuery(params, { page: String(Math.max(0, page - 1)) })}`}
            aria-disabled={page === 0}
            className={`rounded-[9px] border border-[var(--border)] px-3 py-1.5 text-[12.5px] font-medium ${
              page === 0
                ? "pointer-events-none text-[var(--text-3)]"
                : "text-[var(--text-2)] hover:bg-[var(--surface-2)]"
            }`}
          >
            Previous
          </Link>
          <Link
            href={`/audit-log?${buildQuery(params, { page: String(page + 1) })}`}
            aria-disabled={page + 1 >= totalPages}
            className={`rounded-[9px] border border-[var(--border)] px-3 py-1.5 text-[12.5px] font-medium ${
              page + 1 >= totalPages
                ? "pointer-events-none text-[var(--text-3)]"
                : "text-[var(--text-2)] hover:bg-[var(--surface-2)]"
            }`}
          >
            Next
          </Link>
        </div>
      </div>
    </div>
  );
}
