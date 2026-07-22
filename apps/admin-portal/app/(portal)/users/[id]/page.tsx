import { cookies } from "next/headers";
import Link from "next/link";
import { getUserDetail } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { verifyAccessToken } from "@/lib/session";
import { UserActions } from "../UserActions";

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
  const { user, brokerAccounts, mtTerminals } = detail;
  // Bugfix follow-up — only show the MT4/MT5 terminal section when it's actually relevant: either
  // this user has at least one MT4/MT5 account, or mt-terminal-host is unreachable while they do
  // (mtTerminals.reachable === true with zero terminals means neither applies, so most users —
  // CTRADER-only — never see an empty section here).
  const showMtTerminals = mtTerminals.terminals.length > 0 || !mtTerminals.reachable;

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
        {isAdmin && <UserActions user={user} />}
      </div>

      <div className="mt-4 flex flex-wrap gap-2">
        <span
          className={`rounded-full px-2.5 py-1 text-[12px] font-semibold ${
            user.status === "ACTIVE"
              ? "bg-[var(--pos)]/15 text-[var(--pos)]"
              : user.status === "DELETED"
                ? "bg-[var(--surface-2)] text-[var(--text-3)]"
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
              {["Broker", "Platform", "Login", "Status", "Last health check"].map((heading) => (
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
                <td colSpan={5} className="px-5 py-8 text-center text-[var(--text-3)]">
                  No linked broker accounts.
                </td>
              </tr>
            )}
            {brokerAccounts.map((account) => (
              <tr key={account.id} className="border-b border-[var(--border)] last:border-0">
                <td className="px-5 py-2.5 text-[var(--text)]">{account.brokerName ?? "—"}</td>
                <td className="px-5 py-2.5 text-[var(--text-2)]">{account.brokerType}</td>
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

      {showMtTerminals && (
        <>
          <h2 className="mt-8 text-[16px] font-semibold text-[var(--text)]">
            MT4/MT5 terminal status
          </h2>
          <div className="mt-3 rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5">
            {!mtTerminals.reachable ? (
              <p className="text-[13px] text-[var(--neg)]">
                mt-terminal-host is unreachable — pod status is currently unavailable (this is
                distinct from &ldquo;no terminal provisioned&rdquo;, see the account&apos;s Status
                column above).
              </p>
            ) : (
              <table className="w-full border-collapse text-[13px]">
                <thead>
                  <tr className="border-b border-[var(--border)] text-left">
                    <th className="pb-2 text-[11.5px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
                      Account
                    </th>
                    <th className="pb-2 text-[11.5px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
                      Pod status
                    </th>
                    <th className="pb-2 text-right text-[11.5px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
                      Restarts
                    </th>
                    <th className="pb-2 text-right text-[11.5px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
                      Last transition
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {mtTerminals.terminals.map((terminal) => (
                    <tr
                      key={terminal.brokerAccountId}
                      className="border-b border-[var(--border)] last:border-0"
                    >
                      <td className="py-2 text-[var(--text)]">
                        {terminal.brokerType} · {terminal.brokerAccountLogin}
                      </td>
                      <td className="py-2">
                        {!terminal.podProvisioned ? (
                          <span className="rounded-full bg-[var(--surface-2)] px-2.5 py-1 text-[12px] font-semibold text-[var(--text-2)]">
                            No pod
                          </span>
                        ) : (
                          <span
                            className={`rounded-full px-2.5 py-1 text-[12px] font-semibold ${
                              terminal.podReady
                                ? "bg-[var(--pos)]/15 text-[var(--pos)]"
                                : "bg-[var(--neg)]/15 text-[var(--neg)]"
                            }`}
                          >
                            {terminal.podWaitingReason || terminal.podPhase}
                          </span>
                        )}
                      </td>
                      <td
                        className={`py-2 text-right font-mono ${
                          terminal.podProvisioned && (terminal.podRestartCount ?? 0) > 0
                            ? "text-[var(--neg)]"
                            : "text-[var(--text)]"
                        }`}
                      >
                        {terminal.podProvisioned ? terminal.podRestartCount : "—"}
                      </td>
                      <td className="py-2 text-right text-[var(--text-2)]">
                        {terminal.podLastTransitionTime
                          ? new Date(terminal.podLastTransitionTime).toLocaleString()
                          : "—"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </>
      )}
    </div>
  );
}
