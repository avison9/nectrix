import { getPublicMasterProfile } from "@nectrix/api-client";
import type { LeaderboardPeriod } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { fetchOrNotFound } from "@/lib/fetchOrNotFound";
import { RiskDisclosureBanner } from "@/components/RiskDisclosureBanner";

const PERIODS: LeaderboardPeriod[] = ["7D", "30D", "90D", "YTD", "ALL"];

function initials(name: string): string {
  const parts = name.trim().split(/\s+/);
  return parts
    .slice(0, 2)
    .map((p) => p[0]?.toUpperCase() ?? "")
    .join("");
}

export default async function MasterPublicProfilePage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const profile = await fetchOrNotFound(getPublicMasterProfile(coreAppBaseUrl(), id));

  return (
    <>
      <RiskDisclosureBanner />
      <main className="mx-auto max-w-[720px] px-4 py-10">
        <div className="mb-7 flex items-start gap-4">
          <div className="flex h-[58px] w-[58px] flex-none items-center justify-center rounded-full bg-[var(--accent)] text-lg font-semibold text-white">
            {initials(profile.displayName)}
          </div>
          <div className="min-w-0 flex-1">
            <h1 className="text-[22px] font-semibold tracking-tight text-[var(--text)]">
              {profile.displayName}
            </h1>
            {profile.strategyTags.length > 0 && (
              <p className="mt-1 text-[13px] text-[var(--text-3)]">{profile.strategyTags.join(", ")}</p>
            )}
            {profile.verifiedAt && (
              <span className="mt-1.5 inline-block rounded-full bg-[var(--accent-2)] px-2 py-0.5 text-[11px] font-semibold text-[var(--accent)]">
                Verified
              </span>
            )}
          </div>
          {/* Matches the mock's "Request invite" CTA — inert, TICKET-118 isn't built yet. */}
          <button
            type="button"
            disabled
            title="Invitations aren't live yet"
            className="h-10 flex-none cursor-not-allowed rounded-[10px] border border-[var(--border)] px-4 text-[13px] font-semibold text-[var(--text-3)]"
          >
            Request invite
          </button>
        </div>

        {profile.bio && (
          <p className="mb-7 text-[13.5px] leading-[1.6] text-[var(--text-2)]">{profile.bio}</p>
        )}

        <div className="mb-7 grid grid-cols-3 gap-3">
          <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-4">
            <div className="text-[11px] font-medium text-[var(--text-2)]">Performance fee</div>
            <div className="mt-1 font-mono text-lg font-semibold text-[var(--text)]">
              {profile.performanceFeePercent}%
            </div>
          </div>
          <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-4">
            <div className="text-[11px] font-medium text-[var(--text-2)]">Followers</div>
            <div className="mt-1 font-mono text-lg font-semibold text-[var(--text)]">
              {profile.metricsByPeriod.ALL?.followerCount ?? 0}
            </div>
          </div>
          <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-4">
            <div className="text-[11px] font-medium text-[var(--text-2)]">AUM proxy</div>
            <div className="mt-1 font-mono text-lg font-semibold text-[var(--text)]">
              {profile.metricsByPeriod.ALL?.aumProxy?.toLocaleString(undefined, {
                maximumFractionDigits: 0,
              }) ?? "—"}
            </div>
          </div>
        </div>

        <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] overflow-hidden">
          <div className="border-b border-[var(--border)] px-5 py-3.5 text-[13px] font-semibold text-[var(--text)]">
            Verified performance
          </div>
          <table className="w-full text-[13px]">
            <thead>
              <tr className="border-b border-[var(--border)] text-[11px] uppercase tracking-wide text-[var(--text-3)]">
                <th className="px-5 py-2.5 text-left font-semibold">Period</th>
                <th className="px-5 py-2.5 text-right font-semibold">Return</th>
                <th className="px-5 py-2.5 text-right font-semibold">Max drawdown</th>
                <th className="px-5 py-2.5 text-right font-semibold">Win rate</th>
                <th className="px-5 py-2.5 text-right font-semibold">Sharpe-like</th>
              </tr>
            </thead>
            <tbody>
              {PERIODS.map((period) => {
                const m = profile.metricsByPeriod[period];
                return (
                  <tr key={period} className="border-b border-[var(--border)] last:border-b-0">
                    <td className="px-5 py-2.5 font-medium text-[var(--text)]">{period}</td>
                    <td
                      className={`px-5 py-2.5 text-right font-mono ${
                        m ? (m.returnPct >= 0 ? "text-[var(--pos)]" : "text-[var(--neg)]") : "text-[var(--text-3)]"
                      }`}
                    >
                      {m ? `${m.returnPct >= 0 ? "+" : ""}${m.returnPct.toFixed(1)}%` : "—"}
                    </td>
                    <td className="px-5 py-2.5 text-right font-mono text-[var(--text)]">
                      {m ? `${m.maxDrawdownPct.toFixed(1)}%` : "—"}
                    </td>
                    <td className="px-5 py-2.5 text-right font-mono text-[var(--text)]">
                      {m?.winRatePct != null ? `${m.winRatePct.toFixed(1)}%` : "—"}
                    </td>
                    <td className="px-5 py-2.5 text-right font-mono text-[var(--text)]">
                      {m?.sharpeLikeRatio != null ? m.sharpeLikeRatio.toFixed(2) : "—"}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        <p className="mt-3 text-[12px] text-[var(--text-3)]">
          &ldquo;Sharpe-like&rdquo; — no risk-free-rate benchmark, not a rigorous academic Sharpe
          ratio. Computed from actual copied-trade and account data on this platform, not
          self-reported.
        </p>
      </main>
    </>
  );
}
