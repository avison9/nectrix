import {
  getBrokerAccountSnapshot,
  listAllCopiedTrades,
  listCopyRelationships,
} from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { CopyRelationshipStatusBadge } from "@/components/CopyRelationshipStatusBadge";
import { CompactRelationshipActions } from "@/components/CompactRelationshipActions";
import { LivePositionsFeed, type FeedSubscription } from "@/components/LivePositionsFeed";

/**
 * TICKET-116 — mirrors Nectrix.dc.html's `FOLLOWER · DASHBOARD` (`vFollowerDash`, `:1298-1339`): KPI
 * row + a masters-you-copy list, translated into this app's real Tailwind convention with real data
 * rather than the mock's own hardcoded equity-curve SVG path. Pause/resume/stop are real here (this
 * is the follower-facing ownership CopyRelationshipService's own class Javadoc describes).
 */
export async function FollowerDashboard({ email, accessToken }: { email: string; accessToken: string }) {
  const baseUrl = coreAppBaseUrl();
  const relationships = await listCopyRelationships(baseUrl, accessToken, { role: "follower" });
  const activeRelationships = relationships.filter(
    (r) => r.status === "ACTIVE" || r.status === "PAUSED",
  );
  const uniqueBrokerAccountIds = [
    ...new Set(activeRelationships.map((r) => r.followerBrokerAccountId)),
  ];
  const [snapshots, openTradesPage] = await Promise.all([
    Promise.all(
      uniqueBrokerAccountIds.map((id) =>
        getBrokerAccountSnapshot(baseUrl, accessToken, id).catch(() => null),
      ),
    ),
    listAllCopiedTrades(baseUrl, accessToken, { role: "follower", pageSize: 50 }),
  ]);
  // TICKET-124 — Live Activity's own position book, same enriched data Trade History uses.
  const openPositions = openTradesPage.trades.filter(
    (t) => t.status === "FILLED" || t.status === "PARTIALLY_CLOSED",
  );

  const totalEquity = snapshots.reduce((sum, s) => sum + (s?.equity ?? 0), 0);
  // Bugfix — this used to sum every RAW open position on the broker account (including manual,
  // never-copied trades), not just the ones Nectrix actually copied — same source Live Activity
  // already uses below, so the two numbers can never disagree again.
  const openPositionsCount = openPositions.length;
  const activeCount = relationships.filter((r) => r.status === "ACTIVE").length;

  const kpis = [
    { label: "Total equity", value: `$${totalEquity.toLocaleString(undefined, { maximumFractionDigits: 2 })}` },
    { label: "Masters copying", value: String(activeCount) },
    { label: "Open positions", value: String(openPositionsCount) },
    { label: "Total relationships", value: String(relationships.length) },
  ];

  const subscriptions: FeedSubscription[] = activeRelationships.map((r) => ({
    channel: "copy-relationships",
    id: r.id,
    label: `Relationship ${r.id.slice(0, 8)}…`,
  }));

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">
          Welcome back, {email}
        </h1>
        <p className="mt-1.5 text-sm text-[var(--text-2)]">
          Your account is copying <strong>{activeCount}</strong>{" "}
          {activeCount === 1 ? "master" : "masters"}. Trades mirror automatically — nothing to
          manage day to day.
        </p>
      </div>

      <div className="mb-4 grid grid-cols-[repeat(auto-fit,minmax(210px,1fr))] gap-3.5">
        {kpis.map((k) => (
          <div
            key={k.label}
            className="flex flex-col gap-2.5 rounded-[14px] border border-[var(--border)] bg-[var(--surface)] p-4"
          >
            <span className="text-[12.5px] font-medium text-[var(--text-2)]">{k.label}</span>
            <span className="font-mono text-[25px] font-semibold tracking-tight text-[var(--text)]">
              {k.value}
            </span>
          </div>
        ))}
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[1.4fr_1fr] lg:items-start">
        <div className="overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)]">
          <div className="flex items-center justify-between border-b border-[var(--border)] px-5 py-3.5">
            <span className="text-[14px] font-semibold text-[var(--text)]">Masters you copy</span>
          </div>
          {relationships.length === 0 ? (
            <p className="px-5 py-8 text-center text-[13px] text-[var(--text-2)]">
              You&apos;re not copying any Masters yet.
            </p>
          ) : (
            <ul className="flex flex-col">
              {relationships.map((r) => (
                <li
                  key={r.id}
                  className="flex flex-wrap items-center justify-between gap-3 border-t border-[var(--border)] px-5 py-3 first:border-t-0"
                >
                  <div>
                    <div className="text-[13.5px] font-semibold text-[var(--text)]">
                      {r.masterDisplayName ?? `${r.id.slice(0, 8)}…`}
                    </div>
                    <div className="mt-0.5 text-[12px] text-[var(--text-2)]">
                      {r.copyDirection} · fee {r.feeCollectionMethod}
                    </div>
                  </div>
                  <div className="flex items-center gap-3">
                    <CopyRelationshipStatusBadge status={r.status} />
                    <CompactRelationshipActions relationship={r} />
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>

        <LivePositionsFeed
          accessToken={accessToken}
          subscriptions={subscriptions}
          role="follower"
          initialPositions={openPositions}
        />
      </div>
    </div>
  );
}
