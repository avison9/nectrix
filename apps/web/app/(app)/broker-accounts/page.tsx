import Link from "next/link";
import { getBrokerAccountSnapshot, listBrokerAccounts } from "@nectrix/api-client";
import type { BrokerAccountSnapshot, BrokerAccountSummary, BrokerType } from "@nectrix/domain-model";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";
import { DemoLiveTag } from "@/components/DemoLiveTag";
import { ConnectionStatusBadge } from "@/components/ConnectionStatusBadge";
import { DisconnectButton } from "./DisconnectButton";
import { DeleteButton } from "./DeleteButton";
import { ReconnectButton } from "./ReconnectButton";

const PLATFORM_LABEL: Record<BrokerType, string> = {
  CTRADER: "cTrader",
  MT5: "MT5",
  MT4: "MT4",
};

// apps/broker-adapters' snapshot.currency is still a real TODO (the assetId->currency-code lookup
// isn't built yet — see snapshot.go), so it can arrive empty even for a connected account. Falls
// back to the account's own (reliable) currency, then a plain, uncrashable "value BAL" rendering
// rather than letting Intl.NumberFormat throw RangeError on a blank/invalid ISO code.
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
 * Mirrors Nectrix.dc.html's `MASTER · ACCOUNTS` card grid (`vMasterAccounts`, `:946-994`) — real
 * data throughout. `leverage` is real for cTrader (ProtoOATrader.leverageInCents), "—" for MT4/MT5
 * (no leverage field in that wire protocol yet, a genuine EA-side gap). `server` is real for
 * MT4/MT5 (user-entered at link time), never populated for cTrader (no server concept there).
 * Snapshot fetches run in parallel and tolerate individual failures (e.g. a disconnected account) —
 * one bad snapshot shouldn't blank the whole page.
 */
export default async function BrokerAccountsPage() {
  const { accessToken } = await requireSession();
  const accounts = await listBrokerAccounts(coreAppBaseUrl(), accessToken);

  const snapshots = new Map<string, BrokerAccountSnapshot>();
  await Promise.all(
    accounts.map(async (account) => {
      try {
        snapshots.set(account.id, await getBrokerAccountSnapshot(coreAppBaseUrl(), accessToken, account.id));
      } catch {
        // No snapshot available (e.g. disconnected) — card falls back to "—" for balance/equity.
      }
    }),
  );

  return (
    <div className="mx-auto max-w-[1080px]">
      <div className="mb-6">
        <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">
          Connected accounts
        </h1>
        <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
          Broker accounts linked to your Nectrix profile.
        </p>
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {accounts.map((account) => {
          const snapshot = snapshots.get(account.id);
          return <AccountCard key={account.id} account={account} snapshot={snapshot} accessToken={accessToken} />;
        })}

        <Link
          href="/broker-accounts/link"
          className="flex min-h-[200px] flex-col items-center justify-center gap-3 rounded-2xl border-[1.5px] border-dashed border-[var(--border)] p-5 text-[var(--text-3)] transition-colors hover:border-[var(--accent)] hover:text-[var(--accent)]"
        >
          <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-[var(--surface-2)]">
            <svg viewBox="0 0 24 24" width={20} height={20} fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round">
              <path d="M12 5v14M5 12h14" />
            </svg>
          </div>
          <div className="text-[13.5px] font-semibold">Connect a broker</div>
        </Link>
      </div>
    </div>
  );
}

function AccountCard({
  account,
  snapshot,
  accessToken,
}: {
  account: BrokerAccountSummary;
  snapshot: BrokerAccountSnapshot | undefined;
  accessToken: string;
}) {
  const initial = (account.brokerName ?? account.displayLabel ?? account.brokerType)
    .slice(0, 1)
    .toUpperCase();

  return (
    <div className="flex flex-col rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5">
      <div className="mb-4 flex items-center gap-3">
        <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-[11px] border border-[var(--border)] bg-[var(--surface-2)] text-[15px] font-bold text-[var(--text)]">
          {initial}
        </div>
        <div className="min-w-0 flex-1">
          <div className="truncate text-[14.5px] font-semibold text-[var(--text)]">
            {account.displayLabel ?? account.brokerAccountLogin}
          </div>
          <div className="truncate font-mono text-[12px] text-[var(--text-3)]">
            #{account.brokerAccountLogin} · {account.connectionRole}
          </div>
        </div>
        <div className="flex flex-col items-end gap-1">
          <DemoLiveTag isDemo={account.isDemo} />
          {account.serverName && (
            <span className="max-w-[110px] truncate font-mono text-[10.5px] text-[var(--text-3)]">
              {account.serverName}
            </span>
          )}
        </div>
      </div>

      <div className="grid grid-cols-2 gap-3.5 border-y border-[var(--border)] py-4">
        <div>
          <div className="text-[11px] text-[var(--text-3)]">Balance</div>
          <div className="mt-0.5 font-mono text-[15px] font-semibold text-[var(--text)]">
            {snapshot ? money(snapshot.balance, snapshot.currency || account.currency) : "—"}
          </div>
        </div>
        <div>
          <div className="text-[11px] text-[var(--text-3)]">Equity</div>
          <div className="mt-0.5 font-mono text-[15px] font-semibold text-[var(--text)]">
            {snapshot ? money(snapshot.equity, snapshot.currency || account.currency) : "—"}
          </div>
        </div>
        <div>
          <div className="text-[11px] text-[var(--text-3)]">Broker</div>
          <div className="mt-0.5 text-[13.5px] text-[var(--text-2)]">
            {account.brokerName ?? account.brokerType}
          </div>
        </div>
        <div>
          <div className="text-[11px] text-[var(--text-3)]">Platform</div>
          <div className="mt-0.5 text-[13.5px] text-[var(--text-2)]">{PLATFORM_LABEL[account.brokerType]}</div>
        </div>
        <div>
          <div className="text-[11px] text-[var(--text-3)]">Currency</div>
          <div className="mt-0.5 font-mono text-[13px] text-[var(--text-2)]">{account.currency}</div>
        </div>
        <div>
          <div className="text-[11px] text-[var(--text-3)]">Leverage</div>
          {/* Real for cTrader (ProtoOATrader.leverageInCents). MT4/MT5 shows "—" — that wire
              protocol carries no leverage field yet, a genuine EA-side gap, not a display bug. */}
          <div className="mt-0.5 font-mono text-[13px] text-[var(--text-2)]">
            {snapshot?.leverage || "—"}
          </div>
        </div>
      </div>

      <div className="mt-3.5 flex items-center justify-between gap-2">
        <ConnectionStatusBadge
          brokerAccountId={account.id}
          initialStatus={account.connectionStatus}
          accessToken={accessToken}
        />
        <div className="flex gap-2">
          <Link
            href={`/broker-accounts/${account.id}`}
            className="flex h-9 items-center rounded-[9px] border border-[var(--border)] px-3 text-[12.5px] font-semibold text-[var(--text)] transition-colors hover:bg-[var(--surface-2)]"
          >
            Manage
          </Link>
          {account.connectionStatus === "DISCONNECTED" ? (
            <>
              <ReconnectButton
                id={account.id}
                label={account.displayLabel ?? account.brokerAccountLogin}
              />
              <DeleteButton
                id={account.id}
                label={account.displayLabel ?? account.brokerAccountLogin}
              />
            </>
          ) : (
            <DisconnectButton
              id={account.id}
              label={account.displayLabel ?? account.brokerAccountLogin}
            />
          )}
        </div>
      </div>
    </div>
  );
}
