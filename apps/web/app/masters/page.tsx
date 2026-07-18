import Link from "next/link";
import { listLeaderboard } from "@nectrix/api-client";
import type { LeaderboardEntry, LeaderboardPeriod, LeaderboardSort } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { getOptionalSession } from "@/lib/auth";
import { AppShell } from "@/components/AppShell";
import { RiskDisclosureBanner } from "@/components/RiskDisclosureBanner";

const PERIODS: LeaderboardPeriod[] = ["7D", "30D", "90D", "YTD", "ALL"];
const SORTS: { value: LeaderboardSort; label: string }[] = [
  { value: "return_pct", label: "Return" },
  { value: "max_drawdown_pct", label: "Lowest drawdown" },
  { value: "follower_count", label: "Followers" },
];
const PAGE_SIZE = 20;

function initials(name: string): string {
  const parts = name.trim().split(/\s+/);
  return parts
    .slice(0, 2)
    .map((p) => p[0]?.toUpperCase() ?? "")
    .join("");
}

/** No real risk-scoring model exists — a simple, flagged heuristic off max drawdown. */
function riskLabel(maxDrawdownPct: number): { label: string; className: string } {
  if (maxDrawdownPct < 10) return { label: "Low risk", className: "text-[var(--pos)]" };
  if (maxDrawdownPct < 25) return { label: "Medium risk", className: "text-[var(--text-2)]" };
  return { label: "High risk", className: "text-[var(--neg)]" };
}

function queryString(params: Record<string, string | number>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    search.set(key, String(value));
  }
  return `?${search.toString()}`;
}

/**
 * Public masters directory (TICKET-112) — reachable by anonymous visitors, so this page can't live
 * under `(app)/` (that group's layout forces a login redirect). Logged-in visitors reaching it via
 * the Sidebar's own "Discover Masters" nav item still get the full authenticated shell around it
 * (see components/AppShell.tsx's own comment) instead of dropping out of it entirely; anonymous
 * visitors get the bare public version, unchanged.
 */
export default async function MastersDirectoryPage({
  searchParams,
}: {
  searchParams: Promise<{ period?: string; sort?: string; page?: string }>;
}) {
  const optionalSession = await getOptionalSession();
  const content = await MastersDirectoryContent({ searchParams });
  if (optionalSession) {
    return (
      <AppShell session={optionalSession.session} accessToken={optionalSession.accessToken}>
        {content}
      </AppShell>
    );
  }
  return <main>{content}</main>;
}

async function MastersDirectoryContent({
  searchParams,
}: {
  searchParams: Promise<{ period?: string; sort?: string; page?: string }>;
}) {
  const params = await searchParams;
  const period = (PERIODS.includes(params.period as LeaderboardPeriod)
    ? params.period
    : "30D") as LeaderboardPeriod;
  const sort = (SORTS.some((s) => s.value === params.sort)
    ? params.sort
    : "return_pct") as LeaderboardSort;
  const page = Math.max(0, Number(params.page) || 0);

  const masters: LeaderboardEntry[] = await listLeaderboard(coreAppBaseUrl(), {
    period,
    sort,
    page,
  });

  return (
    <>
      <RiskDisclosureBanner />
      <div className="mx-auto max-w-[1080px] px-4 py-10">
        <div className="mb-7">
          <div className="mb-2.5 text-xs font-semibold uppercase tracking-wider text-[var(--accent)]">
            Browse masters
          </div>
          <h1 className="text-[26px] font-semibold tracking-tight text-[var(--text)]">
            Find a track record you trust
          </h1>
          <p className="mt-2 max-w-[600px] text-[14px] leading-[1.6] text-[var(--text-2)]">
            Real return history, risk profile and follower count for every master accepting new
            followers.
          </p>
        </div>

        <div className="mb-6 flex flex-wrap items-center gap-4">
          <div className="flex gap-0.5 rounded-[10px] bg-[var(--surface-2)] p-[3px]">
            {PERIODS.map((p) => (
              <Link
                key={p}
                href={queryString({ period: p, sort, page: 0 })}
                className={`rounded-[7px] px-3 py-1.5 text-[12.5px] font-medium ${
                  p === period ? "bg-[var(--surface)] text-[var(--text)]" : "text-[var(--text-2)]"
                }`}
              >
                {p}
              </Link>
            ))}
          </div>
          <div className="flex gap-2">
            {SORTS.map((s) => (
              <Link
                key={s.value}
                href={queryString({ period, sort: s.value, page: 0 })}
                className={`rounded-[9px] border px-3 py-1.5 text-[12.5px] font-medium ${
                  s.value === sort
                    ? "border-[var(--accent)] text-[var(--accent)]"
                    : "border-[var(--border)] text-[var(--text-2)]"
                }`}
              >
                {s.label}
              </Link>
            ))}
          </div>
        </div>

        {masters.length === 0 ? (
          <p className="text-[13.5px] text-[var(--text-2)]">
            No masters have a long enough track record to rank yet.
          </p>
        ) : (
          <div className="grid grid-cols-[repeat(auto-fit,minmax(300px,1fr))] gap-4">
            {masters.map((m) => {
              const risk = riskLabel(m.maxDrawdownPct);
              return (
                <div
                  key={m.masterProfileId}
                  className="flex flex-col gap-3.5 rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5"
                >
                  <div className="flex items-center gap-3">
                    <div className="flex h-[42px] w-[42px] flex-none items-center justify-center rounded-full bg-[var(--accent)] text-sm font-semibold text-white">
                      {initials(m.displayName)}
                    </div>
                    <div className="min-w-0 flex-1">
                      <div className="truncate text-[15px] font-semibold text-[var(--text)]">
                        {m.displayName}
                      </div>
                      <div className="truncate text-[12.5px] text-[var(--text-3)]">
                        {m.strategyTags.slice(0, 2).join(", ") || "—"}
                      </div>
                    </div>
                    <span className={`whitespace-nowrap text-[11.5px] font-semibold ${risk.className}`}>
                      {risk.label}
                    </span>
                  </div>

                  <div className="grid grid-cols-3 gap-2 border-t border-[var(--border)] pt-3.5">
                    <div>
                      <div className="mb-0.5 text-[10.5px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
                        {period} return
                      </div>
                      <div
                        className={`font-mono text-sm font-semibold ${m.returnPct >= 0 ? "text-[var(--pos)]" : "text-[var(--neg)]"}`}
                      >
                        {m.returnPct >= 0 ? "+" : ""}
                        {m.returnPct.toFixed(1)}%
                      </div>
                    </div>
                    <div>
                      <div className="mb-0.5 text-[10.5px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
                        Max drawdown
                      </div>
                      <div className="font-mono text-sm font-semibold text-[var(--text)]">
                        {m.maxDrawdownPct.toFixed(1)}%
                      </div>
                    </div>
                    <div>
                      <div className="mb-0.5 text-[10.5px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
                        Followers
                      </div>
                      <div className="font-mono text-sm font-semibold text-[var(--text)]">
                        {m.followerCount}
                      </div>
                    </div>
                  </div>

                  <Link
                    href={`/masters/${m.masterProfileId}`}
                    className="rounded-[11px] border border-[var(--border)] py-2.5 text-center text-[13.5px] font-semibold text-[var(--text)] transition-colors hover:border-[var(--accent)] hover:text-[var(--accent)]"
                  >
                    View profile
                  </Link>
                  {/* Matches the mock's "Request invite from {name}" button — rendered but inert:
                      TICKET-118 (invitations) isn't built yet, so there's nothing for it to do. */}
                  <button
                    type="button"
                    disabled
                    title="Invitations aren't live yet"
                    className="cursor-not-allowed rounded-[11px] border border-[var(--border)] py-2.5 text-center text-[13.5px] font-semibold text-[var(--text-3)]"
                  >
                    Request invite from {m.displayName.split(" ")[0]}
                  </button>
                </div>
              );
            })}
          </div>
        )}

        <div className="mt-6 flex justify-between">
          {page > 0 ? (
            <Link
              href={queryString({ period, sort, page: page - 1 })}
              className="text-[12.5px] font-medium text-[var(--text-2)] underline-offset-2 hover:underline"
            >
              Previous
            </Link>
          ) : (
            <span />
          )}
          {masters.length === PAGE_SIZE && (
            <Link
              href={queryString({ period, sort, page: page + 1 })}
              className="text-[12.5px] font-medium text-[var(--text-2)] underline-offset-2 hover:underline"
            >
              Next
            </Link>
          )}
        </div>
      </div>
    </>
  );
}
