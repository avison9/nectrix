import {
  getBrokerAccountSnapshot,
  listAllCopiedTrades,
  listBrokerAccounts,
  listCopyRelationships,
} from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { CopyRelationshipStatusBadge } from "@/components/CopyRelationshipStatusBadge";
import { LivePositionsFeed, type FeedSubscription } from "@/components/LivePositionsFeed";

/**
 * TICKET-116 — mirrors Nectrix.dc.html's `MASTER · DASHBOARD` (`vMasterDash`, `:360-546`): KPI row +
 * followers table, translated into this app's real Tailwind convention with real data rather than
 * the mock's own hardcoded equity-curve SVG paths (a full period-toggling equity chart is
 * /analytics's own job, TICKET-116's Master Analytics page). No pause/resume/stop actions here —
 * CopyRelationshipService's own ownership check is follower-facing only (see its class Javadoc), a
 * Master calling those would get a 403.
 */
export async function MasterDashboard({ accessToken }: { accessToken: string }) {
  const baseUrl = coreAppBaseUrl();
  const [brokerAccounts, relationships, openTradesPage] = await Promise.all([
    listBrokerAccounts(baseUrl, accessToken),
    listCopyRelationships(baseUrl, accessToken, { role: "master" }),
    listAllCopiedTrades(baseUrl, accessToken, { role: "master", pageSize: 50 }),
  ]);
  const masterAccounts = brokerAccounts.filter((a) => a.connectionRole !== "FOLLOWER_ONLY");
  const snapshots = await Promise.all(
    masterAccounts.map((a) =>
      getBrokerAccountSnapshot(baseUrl, accessToken, a.id).catch(() => null),
    ),
  );
  // TICKET-124 — Live Activity's own position book, same enriched data Trade History uses.
  const openPositions = openTradesPage.trades.filter(
    (t) => t.status === "FILLED" || t.status === "PARTIALLY_CLOSED",
  );

  const totalEquity = snapshots.reduce((sum, s) => sum + (s?.equity ?? 0), 0);
  const activeCount = relationships.filter((r) => r.status === "ACTIVE").length;
  const pausedCount = relationships.filter((r) => r.status === "PAUSED").length;

  const kpis = [
    { label: "Total equity", value: `$${totalEquity.toLocaleString(undefined, { maximumFractionDigits: 2 })}` },
    { label: "Active followers", value: String(activeCount) },
    { label: "Paused followers", value: String(pausedCount) },
    { label: "Total relationships", value: String(relationships.length) },
  ];

  const subscriptions: FeedSubscription[] = masterAccounts.map((a) => ({
    channel: "positions",
    id: a.id,
    label: a.displayLabel ?? a.brokerAccountLogin,
  }));

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">Overview</h1>
        <p className="mt-1.5 text-sm text-[var(--text-2)]">
          Master account performance and copied activity across your network.
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
            <span className="text-[14px] font-semibold text-[var(--text)]">Followers</span>
          </div>
          {relationships.length === 0 ? (
            <p className="px-5 py-8 text-center text-[13px] text-[var(--text-2)]">
              No followers are copying you yet.
            </p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[520px] border-collapse">
                <thead>
                  <tr>
                    <th className="whitespace-nowrap px-5 py-2.5 text-left text-[11.5px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
                      Relationship
                    </th>
                    <th className="whitespace-nowrap px-5 py-2.5 text-left text-[11.5px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
                      Copy direction
                    </th>
                    <th className="whitespace-nowrap px-5 py-2.5 text-right text-[11.5px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
                      Status
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {relationships.map((r) => (
                    <tr key={r.id} className="border-t border-[var(--border)]">
                      <td className="px-5 py-3 text-[13px] font-medium text-[var(--text)]">
                        {r.followerDisplayName ?? `${r.id.slice(0, 8)}…`}
                      </td>
                      <td className="px-5 py-3 text-[13px] text-[var(--text-2)]">{r.copyDirection}</td>
                      <td className="px-5 py-3 text-right">
                        <CopyRelationshipStatusBadge status={r.status} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>

        <LivePositionsFeed
          accessToken={accessToken}
          subscriptions={subscriptions}
          role="master"
          initialPositions={openPositions}
        />
      </div>
    </div>
  );
}
