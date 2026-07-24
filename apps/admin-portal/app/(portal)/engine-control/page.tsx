import { cookies } from "next/headers";
import { getEngineStatus } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { verifyAccessToken } from "@/lib/session";
import { EngineActions } from "./EngineActions";

/**
 * Engine Control — the technical counterpart to Audit Log: restart/stop/start the Go engines that
 * power the platform (broker-adapters/copy-engine/mt5-bridge-gateway/mt-terminal-host) plus
 * Redis, and see each one's own live status at a glance. See AdminController#getEngineStatus's own
 * Javadoc for the exact state model each engine gets (4-state for the three reconcile-loop
 * services, 2-state for mt-terminal-host/Redis).
 */
export default async function EngineControlPage() {
  const jar = await cookies();
  // middleware.ts + (portal)/layout.tsx already guarantee a valid ADMIN/SUPPORT session reaches
  // this far.
  const accessToken = jar.get("access_token")!.value;
  const session = await verifyAccessToken(accessToken);
  const isAdmin = !!session?.roles.includes("ADMIN");

  const engines = await getEngineStatus(coreAppBaseUrl(), accessToken);

  return (
    <div>
      <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">
        Engine Control
      </h1>
      <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
        Live status for every engine that powers the platform — restart, stop, or start any of
        them locally and watch the status update.
      </p>

      <div className="mt-6 grid grid-cols-1 gap-4 md:grid-cols-2">
        {engines.map((engine) => (
          <section
            key={engine.serviceId}
            className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5"
          >
            <div className="flex items-start justify-between gap-3">
              <div>
                <h2 className="text-[15px] font-semibold text-[var(--text)]">
                  {engine.displayName}
                </h2>
                <span
                  className={`mt-2 inline-flex items-center rounded-full px-2.5 py-1 text-[12px] font-semibold ${
                    engine.status === "CONNECTED"
                      ? "bg-[var(--pos)]/15 text-[var(--pos)]"
                      : engine.status === "IDLE"
                        ? "bg-[var(--surface-2)] text-[var(--text-2)]"
                        : engine.status === "STALE"
                          ? "bg-amber-500/15 text-amber-600"
                          : "bg-[var(--neg)]/15 text-[var(--neg)]"
                  }`}
                >
                  {engine.status}
                </span>
              </div>
              {isAdmin && (
                <EngineActions serviceId={engine.serviceId} displayName={engine.displayName} />
              )}
            </div>

            <dl className="mt-4 flex flex-wrap gap-x-6 gap-y-2 text-[13px]">
              {engine.connectedCount !== null && (
                <div>
                  <dt className="text-[11.5px] uppercase tracking-wide text-[var(--text-3)]">
                    Connected
                  </dt>
                  <dd className="font-mono text-[var(--text)]">{engine.connectedCount}</dd>
                </div>
              )}
              {engine.lastReconcileAt !== null && (
                <div>
                  <dt className="text-[11.5px] uppercase tracking-wide text-[var(--text-3)]">
                    Last reconciled
                  </dt>
                  <dd className="text-[var(--text-2)]">
                    {new Date(engine.lastReconcileAt).toLocaleString()}
                  </dd>
                </div>
              )}
              {engine.latencyMs !== null && (
                <div>
                  <dt className="text-[11.5px] uppercase tracking-wide text-[var(--text-3)]">
                    Latency
                  </dt>
                  <dd className="font-mono text-[var(--text)]">{engine.latencyMs}ms</dd>
                </div>
              )}
            </dl>
          </section>
        ))}
      </div>
    </div>
  );
}
