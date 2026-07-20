import Link from "next/link";
import {
  getBrokerAccount,
  getBrokerAccountSnapshot,
  getPublicMasterProfile,
  listCopyRelationships,
} from "@nectrix/api-client";
import type { BrokerType } from "@nectrix/domain-model";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";
import { fetchOrNotFound } from "@/lib/fetchOrNotFound";
import { ConnectionStatusBadge } from "@/components/ConnectionStatusBadge";
import { DemoLiveTag } from "@/components/DemoLiveTag";

const PLATFORM_LABEL: Record<BrokerType, string> = {
  CTRADER: "cTrader",
  MT5: "MT5",
  MT4: "MT4",
};

function money(value: number, currency: string): string {
  if (!currency || currency.length !== 3) {
    return `${value.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${currency || "?"}`;
  }
  try {
    return new Intl.NumberFormat("en-US", { style: "currency", currency }).format(value);
  } catch {
    return `${value.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${currency}`;
  }
}

/**
 * Mirrors Nectrix.dc.html's `MASTER · ACCOUNT VIEW (managed)` (`vMasterAccountView`, `:995-1043`) —
 * the mock has no separate Follower variant, so this page derives which sections to show from the
 * account's own `connectionRole` (MASTER_ONLY/FOLLOWER_ONLY/BOTH shows both). Deliberately honest
 * about what's real vs. not yet built:
 * - Balance/Equity/Leverage: real (AccountSnapshot) for cTrader; leverage shows "—" for MT4/MT5,
 *   whose own EA wire protocol carries no leverage field yet (a real gap, not unwired plumbing).
 * - "Copying to" (Master side): real count of this Master's ACTIVE copy relationships. Phase 1 has
 *   one master_profile per Master, so this is the Master's total, not strictly scoped to only
 *   relationships originating from THIS specific broker account — a disclosed approximation.
 * - "Managed by" (Follower side): real — the Master on the one CopyRelationship whose
 *   followerBrokerAccountId matches this account, via the same public-profile lookup
 *   /copy-relationships already uses.
 * - "Broadcast trades to followers": no real per-account on/off flag exists in the backend, so
 *   this renders as a status readout (from connectionStatus), never an interactive toggle a click
 *   would silently no-op.
 * - "Risk cap per follower": no Master-level cap concept exists yet (each Follower's own risk
 *   profile is set on their own CopyRelationship, not the Master's account) — "—".
 */
export default async function BrokerAccountDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { accessToken } = await requireSession();
  const { id } = await params;
  const account = await fetchOrNotFound(getBrokerAccount(coreAppBaseUrl(), accessToken, id));

  let snapshot;
  try {
    snapshot = await getBrokerAccountSnapshot(coreAppBaseUrl(), accessToken, id);
  } catch {
    snapshot = undefined;
  }

  const showsMasterSide = account.connectionRole === "MASTER_ONLY" || account.connectionRole === "BOTH";
  const showsFollowerSide =
    account.connectionRole === "FOLLOWER_ONLY" || account.connectionRole === "BOTH";

  let copyingToCount: number | undefined;
  if (showsMasterSide) {
    const relationships = await listCopyRelationships(coreAppBaseUrl(), accessToken, {
      role: "master",
      status: "ACTIVE",
    });
    copyingToCount = relationships.length;
  }

  let managedByName: string | undefined;
  if (showsFollowerSide) {
    const relationships = await listCopyRelationships(coreAppBaseUrl(), accessToken, {
      role: "follower",
    });
    const mine = relationships.find((r) => r.followerBrokerAccountId === account.id);
    if (mine) {
      try {
        const master = await getPublicMasterProfile(coreAppBaseUrl(), mine.masterProfileId);
        managedByName = master.displayName;
      } catch {
        // Master profile unavailable — "Managed by" row just won't render below.
      }
    }
  }

  const name = account.brokerName ?? account.brokerType;
  const initial = name.slice(0, 1).toUpperCase();

  return (
    <div className="mx-auto max-w-[900px]">
      <Link
        href="/broker-accounts"
        className="mb-4 inline-flex h-[34px] items-center gap-1.5 rounded-[9px] border border-[var(--border)] px-3 text-[13px] font-semibold text-[var(--text-2)] transition-colors hover:bg-[var(--surface-2)]"
      >
        <svg viewBox="0 0 24 24" width={15} height={15} fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
          <path d="M15 18l-6-6 6-6" />
        </svg>
        Back to accounts
      </Link>

      <div className="mb-5.5 flex flex-wrap items-center gap-3.5">
        <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-[13px] border border-[var(--border)] bg-[var(--surface-2)] text-[18px] font-bold text-[var(--text)]">
          {initial}
        </div>
        <div className="min-w-0 flex-1">
          <h1 className="text-[23px] font-semibold tracking-tight text-[var(--text)]">{name}</h1>
          <div className="mt-0.5 font-mono text-[13px] text-[var(--text-3)]">
            #{account.brokerAccountLogin} · {PLATFORM_LABEL[account.brokerType]}
            {account.serverName ? ` · ${account.serverName}` : ""}
          </div>
        </div>
        <DemoLiveTag isDemo={account.isDemo} />
        <ConnectionStatusBadge
          brokerAccountId={account.id}
          initialStatus={account.connectionStatus}
          accessToken={accessToken}
        />
        {showsFollowerSide && managedByName && (
          <span className="rounded-full bg-[var(--pos)]/15 px-2.5 py-1 text-[11.5px] font-semibold text-[var(--pos)]">
            Managed by {managedByName}
          </span>
        )}
      </div>

      <div className="mb-4 grid grid-cols-2 gap-3.5 sm:grid-cols-4">
        <div className="rounded-[14px] border border-[var(--border)] bg-[var(--surface)] p-4">
          <div className="text-[12px] font-medium text-[var(--text-2)]">Balance</div>
          <div className="mt-1.5 font-mono text-[20px] font-semibold tracking-tight text-[var(--text)]">
            {snapshot ? money(snapshot.balance, snapshot.currency || account.currency) : "—"}
          </div>
        </div>
        <div className="rounded-[14px] border border-[var(--border)] bg-[var(--surface)] p-4">
          <div className="text-[12px] font-medium text-[var(--text-2)]">Equity</div>
          <div className="mt-1.5 font-mono text-[20px] font-semibold tracking-tight text-[var(--text)]">
            {snapshot ? money(snapshot.equity, snapshot.currency || account.currency) : "—"}
          </div>
        </div>
        <div className="rounded-[14px] border border-[var(--border)] bg-[var(--surface)] p-4">
          <div className="text-[12px] font-medium text-[var(--text-2)]">Leverage</div>
          {/* Real for cTrader; MT4/MT5 shows "—" — its wire protocol has no leverage field yet. */}
          <div className="mt-1.5 font-mono text-[20px] font-semibold tracking-tight text-[var(--text)]">
            {snapshot?.leverage || "—"}
          </div>
        </div>
        {showsMasterSide && (
          <div className="rounded-[14px] border border-[var(--border)] bg-[var(--surface)] p-4">
            <div className="text-[12px] font-medium text-[var(--text-2)]">Copying to</div>
            <div className="mt-1.5 font-mono text-[20px] font-semibold tracking-tight text-[var(--text)]">
              {copyingToCount}
            </div>
          </div>
        )}
      </div>

      <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] px-5">
        {showsMasterSide && (
          <div className="flex items-center justify-between gap-3 border-b border-[var(--border)] py-4">
            <div>
              <div className="text-[14px] font-semibold text-[var(--text)]">
                Broadcast trades to followers
              </div>
              <div className="mt-0.5 text-[12.5px] text-[var(--text-3)]">
                Reflects this account&apos;s live connection — not a separate switch yet
              </div>
            </div>
            <span
              className={`rounded-full px-2.5 py-1 text-[12px] font-semibold ${
                account.connectionStatus === "CONNECTED"
                  ? "bg-[var(--pos)]/15 text-[var(--pos)]"
                  : "bg-[var(--surface-2)] text-[var(--text-2)]"
              }`}
            >
              {account.connectionStatus === "CONNECTED" ? "Broadcasting" : "Paused"}
            </span>
          </div>
        )}
        <div
          className={`flex items-center justify-between gap-3 py-4 ${showsMasterSide ? "border-b border-[var(--border)]" : ""}`}
        >
          <div>
            <div className="text-[14px] font-semibold text-[var(--text)]">Sync mode</div>
            <div className="mt-0.5 text-[12.5px] text-[var(--text-3)]">
              Trades mirror over Nectrix&apos;s copy engine
            </div>
          </div>
          <span className="font-mono text-[13px] text-[var(--text-2)]">
            {account.connectionStatus === "CONNECTED" ? "Real-time" : "—"}
          </span>
        </div>
        {showsMasterSide && (
          <div className="flex items-center justify-between gap-3 py-4">
            <div>
              <div className="text-[14px] font-semibold text-[var(--text)]">Risk cap per follower</div>
              <div className="mt-0.5 text-[12.5px] text-[var(--text-3)]">
                Not configurable yet — each Follower sets their own risk profile
              </div>
            </div>
            <span className="font-mono text-[13px] text-[var(--text)]">—</span>
          </div>
        )}
      </div>

      <Link
        href={`/broker-accounts/role/${account.id}`}
        className="mt-4 inline-flex h-9 items-center rounded-[9px] border border-[var(--border)] px-3 text-[12.5px] font-semibold text-[var(--text)] transition-colors hover:bg-[var(--surface-2)]"
      >
        Change account role
      </Link>
    </div>
  );
}
