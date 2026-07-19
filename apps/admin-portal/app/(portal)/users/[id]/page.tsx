import { cookies } from "next/headers";
import Link from "next/link";
import { getUserDetail } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { verifyAccessToken } from "@/lib/session";
import { SuspendReinstateButton } from "../SuspendReinstateButton";

/**
 * TICKET-117 — real user detail: profile + every linked broker account (via
 * BrokerAccountLookupApi#listForUser) with its real connectionStatus/lastHealthCheckAt.
 */
export default async function UserDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const jar = await cookies();
  const token = jar.get("access_token")?.value;
  const session = token ? await verifyAccessToken(token) : null;
  const isAdmin = !!session?.roles.includes("ADMIN");
  const accessToken = jar.get("access_token")!.value;

  const detail = await getUserDetail(coreAppBaseUrl(), accessToken, id);
  const { user, brokerAccounts } = detail;

  return (
    <div className="max-w-3xl">
      <Link
        href="/users"
        className="mb-4 inline-flex items-center gap-1.5 text-[13px] font-medium text-[var(--text-2)] hover:text-[var(--accent)]"
      >
        ← Back to users
      </Link>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-[23px] font-semibold tracking-tight text-[var(--text)]">
            {user.displayName}
          </h1>
          <p className="mt-1 text-[14px] text-[var(--text-2)]">{user.email}</p>
        </div>
        {isAdmin && <SuspendReinstateButton userId={user.id} status={user.status} />}
      </div>

      <div className="mt-4 flex flex-wrap gap-2">
        <span
          className={`rounded-full px-2.5 py-1 text-[12px] font-semibold ${
            user.status === "ACTIVE"
              ? "bg-[var(--pos)]/15 text-[var(--pos)]"
              : "bg-[var(--neg)]/15 text-[var(--neg)]"
          }`}
        >
          {user.status}
        </span>
        <span
          className={`rounded-full px-2.5 py-1 text-[12px] font-semibold ${
            user.twoFactorEnabled
              ? "bg-[var(--pos)]/15 text-[var(--pos)]"
              : "bg-[var(--surface-2)] text-[var(--text-3)]"
          }`}
        >
          2FA {user.twoFactorEnabled ? "enabled" : "not enabled"}
        </span>
      </div>

      <h2 className="mt-8 text-[16px] font-semibold text-[var(--text)]">
        Linked broker accounts
      </h2>
      <div className="mt-3 overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)]">
        <table className="w-full border-collapse text-[13px]">
          <thead>
            <tr className="border-b border-[var(--border)] text-left">
              {["Broker", "Login", "Status", "Last health check"].map((heading) => (
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
            {brokerAccounts.length === 0 && (
              <tr>
                <td colSpan={4} className="px-5 py-8 text-center text-[var(--text-3)]">
                  No linked broker accounts.
                </td>
              </tr>
            )}
            {brokerAccounts.map((account) => (
              <tr key={account.id} className="border-b border-[var(--border)] last:border-0">
                <td className="px-5 py-2.5 text-[var(--text)]">{account.brokerType}</td>
                <td className="px-5 py-2.5 font-mono text-[12px] text-[var(--text-2)]">
                  {account.brokerAccountLogin}
                </td>
                <td className="px-5 py-2.5">
                  <span
                    className={`rounded-full px-2.5 py-1 text-[12px] font-semibold ${
                      account.connectionStatus === "CONNECTED"
                        ? "bg-[var(--pos)]/15 text-[var(--pos)]"
                        : account.connectionStatus === "PENDING"
                          ? "bg-[var(--surface-2)] text-[var(--text-2)]"
                          : "bg-[var(--neg)]/15 text-[var(--neg)]"
                    }`}
                  >
                    {account.connectionStatus}
                  </span>
                </td>
                <td className="px-5 py-2.5 text-[var(--text-2)]">
                  {account.lastHealthCheckAt
                    ? new Date(account.lastHealthCheckAt).toLocaleString()
                    : "—"}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
