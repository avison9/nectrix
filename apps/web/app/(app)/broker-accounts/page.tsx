import Link from "next/link";
import { listBrokerAccounts } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";
import { DemoLiveTag } from "@/components/DemoLiveTag";
import { ConnectionStatusBadge } from "@/components/ConnectionStatusBadge";

export default async function BrokerAccountsPage() {
  const { accessToken } = await requireSession();
  const accounts = await listBrokerAccounts(coreAppBaseUrl(), accessToken);

  return (
    <div className="mx-auto max-w-[720px]">
      <div className="flex items-center justify-between">
        <h1 className="text-[20px] font-semibold tracking-tight text-[var(--text)]">
          Your broker accounts
        </h1>
        <Link
          href="/broker-accounts/link"
          className="rounded-[10px] bg-[var(--accent)] px-3.5 py-2 text-[13px] font-semibold text-white"
        >
          Link a broker account
        </Link>
      </div>

      {accounts.length === 0 ? (
        <p className="mt-8 text-[13.5px] text-[var(--text-2)]">
          You haven&apos;t linked a broker account yet.
        </p>
      ) : (
        <ul className="mt-6 flex flex-col gap-3">
          {accounts.map((account) => (
            <li
              key={account.id}
              className="flex items-center justify-between rounded-[12px] border border-[var(--border)] bg-[var(--surface)] p-4"
            >
              <div className="flex flex-col gap-1">
                <div className="flex items-center gap-2">
                  <span className="text-[14px] font-medium text-[var(--text)]">
                    {account.displayLabel ?? account.brokerAccountLogin}
                  </span>
                  <DemoLiveTag isDemo={account.isDemo} />
                </div>
                <span className="text-[12.5px] text-[var(--text-2)]">
                  {account.brokerType} · {account.connectionRole} · {account.currency}
                </span>
              </div>
              <div className="flex items-center gap-3">
                <ConnectionStatusBadge
                  brokerAccountId={account.id}
                  initialStatus={account.connectionStatus}
                  accessToken={accessToken}
                />
                <Link
                  href={`/broker-accounts/role/${account.id}`}
                  className="text-[12.5px] font-medium text-[var(--text-2)] underline-offset-2 hover:underline"
                >
                  Manage
                </Link>
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
