import { cookies } from "next/headers";
import { getSystemHealth } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";

/**
 * TICKET-117 — replaces the StubPage placeholder with real, live metrics: broker-adapter
 * connection counts, Copy Engine throughput/error rate, reconciliation drift rate, and Kafka
 * consumer-group lag. Built from Postgres + a real Kafka AdminClient, not Prometheus — see
 * AdminController#getSystemHealth's own Javadoc for why (no Prometheus anywhere outside local
 * dev/CI, including the one persistent nectrix-dev environment). A genuinely-empty
 * table/topic renders a real "0", never a placeholder number.
 */
export default async function SystemHealthPage() {
  const jar = await cookies();
  // middleware.ts + (portal)/layout.tsx already guarantee a valid ADMIN/SUPPORT/MASTER
  // session reaches this far.
  const accessToken = jar.get("access_token")!.value;

  const health = await getSystemHealth(coreAppBaseUrl(), accessToken);

  const errorRatePct =
    health.copyEngine.tradesInWindow === 0
      ? 0
      : (health.copyEngine.failedInWindow / health.copyEngine.tradesInWindow) * 100;

  return (
    <div>
      <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">
        System Health
      </h1>
      <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
        Broker adapter connection counts, Copy Engine throughput/error rate, reconciliation
        drift rate, and Kafka consumer lag — live, not mock data.
      </p>

      <div className="mt-6 grid grid-cols-1 gap-4 md:grid-cols-2">
        <section className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5">
          <h2 className="text-[13px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
            Broker connections
          </h2>
          {health.brokerConnections.length === 0 ? (
            <p className="mt-4 text-[13px] text-[var(--text-3)]">No broker accounts yet.</p>
          ) : (
            <table className="mt-4 w-full border-collapse text-[13px]">
              <thead>
                <tr className="border-b border-[var(--border)] text-left">
                  <th className="pb-2 text-[11.5px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
                    Broker
                  </th>
                  <th className="pb-2 text-[11.5px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
                    Status
                  </th>
                  <th className="pb-2 text-right text-[11.5px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
                    Count
                  </th>
                </tr>
              </thead>
              <tbody>
                {health.brokerConnections.map((row) => (
                  <tr
                    key={`${row.brokerType}-${row.connectionStatus}`}
                    className="border-b border-[var(--border)] last:border-0"
                  >
                    <td className="py-2 text-[var(--text)]">{row.brokerType}</td>
                    <td className="py-2">
                      <span
                        className={`rounded-full px-2.5 py-1 text-[12px] font-semibold ${
                          row.connectionStatus === "CONNECTED"
                            ? "bg-[var(--pos)]/15 text-[var(--pos)]"
                            : row.connectionStatus === "PENDING"
                              ? "bg-[var(--surface-2)] text-[var(--text-2)]"
                              : "bg-[var(--neg)]/15 text-[var(--neg)]"
                        }`}
                      >
                        {row.connectionStatus}
                      </span>
                    </td>
                    <td className="py-2 text-right font-mono text-[var(--text)]">{row.count}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>

        <section className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5">
          <h2 className="text-[13px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
            Copy Engine (last {health.copyEngine.windowMinutes}m)
          </h2>
          <div className="mt-4 flex items-end gap-6">
            <div>
              <div className="text-[26px] font-semibold text-[var(--text)]">
                {health.copyEngine.tradesInWindow}
              </div>
              <div className="mt-0.5 text-[12px] text-[var(--text-3)]">Trades copied</div>
            </div>
            <div>
              <div
                className={`text-[26px] font-semibold ${errorRatePct > 0 ? "text-[var(--neg)]" : "text-[var(--text)]"}`}
              >
                {errorRatePct.toFixed(1)}%
              </div>
              <div className="mt-0.5 text-[12px] text-[var(--text-3)]">
                Error rate ({health.copyEngine.failedInWindow} failed)
              </div>
            </div>
          </div>
        </section>

        <section className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5">
          <h2 className="text-[13px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
            Reconciliation drift
          </h2>
          <div className="mt-4">
            <div
              className={`text-[26px] font-semibold ${health.reconciliationDriftLastHour > 0 ? "text-[var(--neg)]" : "text-[var(--text)]"}`}
            >
              {health.reconciliationDriftLastHour}
            </div>
            <div className="mt-0.5 text-[12px] text-[var(--text-3)]">Events in the last hour</div>
          </div>
        </section>

        <section className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5">
          <h2 className="text-[13px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
            Kafka consumer lag
          </h2>
          <table className="mt-4 w-full border-collapse text-[13px]">
            <thead>
              <tr className="border-b border-[var(--border)] text-left">
                <th className="pb-2 text-[11.5px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
                  Group
                </th>
                <th className="pb-2 text-[11.5px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
                  Topic
                </th>
                <th className="pb-2 text-right text-[11.5px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
                  Lag
                </th>
              </tr>
            </thead>
            <tbody>
              {health.kafkaConsumerLag.map((row) => (
                <tr key={row.groupId} className="border-b border-[var(--border)] last:border-0">
                  <td className="py-2 font-mono text-[12px] text-[var(--text-2)]">{row.groupId}</td>
                  <td className="py-2 text-[var(--text-2)]">{row.topic}</td>
                  <td
                    className={`py-2 text-right font-mono ${
                      row.lag < 0
                        ? "text-[var(--text-3)]"
                        : row.lag > 0
                          ? "text-[var(--neg)]"
                          : "text-[var(--text)]"
                    }`}
                  >
                    {row.lag < 0 ? "n/a" : row.lag}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>

        <section className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5 md:col-span-2">
          <h2 className="text-[13px] font-semibold uppercase tracking-wide text-[var(--text-3)]">
            MT4/MT5 terminals
          </h2>
          {!health.mtTerminals.reachable ? (
            <p className="mt-4 text-[13px] text-[var(--neg)]">
              mt-terminal-host is unreachable — pod status is currently unavailable (this is
              distinct from &ldquo;no terminals provisioned&rdquo;, see below).
            </p>
          ) : health.mtTerminals.terminals.length === 0 ? (
            <p className="mt-4 text-[13px] text-[var(--text-3)]">No MT4/MT5 accounts linked yet.</p>
          ) : (
            <table className="mt-4 w-full border-collapse text-[13px]">
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
                {health.mtTerminals.terminals.map((terminal) => (
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
        </section>
      </div>
    </div>
  );
}
