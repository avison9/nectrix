import Link from "next/link";
import { getPublicMasterProfile, listCopyRelationships } from "@nectrix/api-client";
import type { CopyRelationship, PublicMasterProfile } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";
import { CopyRelationshipStatusBadge } from "@/components/CopyRelationshipStatusBadge";
import { CopyActionButtons } from "./CopyActionButtons";

function initials(name: string): string {
  return name
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((p) => p[0]?.toUpperCase() ?? "")
    .join("");
}

function copyRatioLabel(mm: CopyRelationship["moneyManagementProfile"]): string {
  if (mm.multiplier !== null) return `${mm.multiplier}×`;
  if (mm.riskPercent !== null) return `${mm.riskPercent}% risk`;
  if (mm.fixedLotSize !== null) return `${mm.fixedLotSize} lots`;
  return "—";
}

function riskLabel(risk: CopyRelationship["riskProfile"]): string {
  if (risk.drawdownPausePct !== null) return `${risk.drawdownPausePct}% DD cap`;
  if (risk.maxLotPerTrade !== null) return `${risk.maxLotPerTrade} lot max`;
  return "—";
}

/**
 * Mirrors Nectrix.dc.html's `FOLLOWER · MY MASTERS / CONNECT` (`vFollowerConnect`, `:1414-1449`) —
 * card grid replacing the old bare `<ul>` placeholder. One deliberate, disclosed deviation from the
 * mock: no "Have an invite code?" input — Followers never self-initiate joining a Master in this
 * domain model (TICKET-118: invitations are Master-created only), so there's no real endpoint that
 * box could ever call. Return·30d/Risk/Copy ratio are all real: return comes from the Master's own
 * public discovery profile (same data /masters/[id] shows), Risk/Copy ratio come from THIS
 * relationship's own riskProfile/moneyManagementProfile (the Follower's own configured settings for
 * copying that Master, not the Master's).
 */
export default async function CopyRelationshipsPage() {
  const { accessToken } = await requireSession();
  const relationships = await listCopyRelationships(coreAppBaseUrl(), accessToken, {
    role: "follower",
  });

  const masters = new Map<string, PublicMasterProfile>();
  await Promise.all(
    [...new Set(relationships.map((cr) => cr.masterProfileId))].map(async (masterProfileId) => {
      try {
        masters.set(masterProfileId, await getPublicMasterProfile(coreAppBaseUrl(), masterProfileId));
      } catch {
        // Master profile unavailable — card falls back to a generic label below.
      }
    }),
  );

  return (
    <div className="mx-auto max-w-[1000px]">
      <div className="mb-6">
        <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">My masters</h1>
        <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
          Masters you copy trades from. You can only join a Master who has invited you.
        </p>
      </div>

      {relationships.length === 0 ? (
        <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-6 text-center text-[13.5px] text-[var(--text-2)]">
          You&apos;re not copying any Masters yet — accept an invite from a Master to get started.
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {relationships.map((cr) => {
            const master = masters.get(cr.masterProfileId);
            const name = master?.displayName ?? "Master";
            const returnPct = master?.metricsByPeriod?.["30D"]?.returnPct;

            return (
              <div
                key={cr.id}
                className="flex flex-col gap-4 rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5"
              >
                <div className="flex items-center gap-3">
                  <div className="flex h-[42px] w-[42px] shrink-0 items-center justify-center rounded-xl bg-[var(--accent-2)] text-[14px] font-bold text-[var(--accent)]">
                    {initials(name)}
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-[15px] font-semibold text-[var(--text)]">{name}</div>
                    <div className="truncate text-[12px] text-[var(--text-3)]">
                      {master?.strategyTags[0] ?? "—"}
                    </div>
                  </div>
                  <CopyRelationshipStatusBadge status={cr.status} />
                </div>

                <div className="grid grid-cols-3 gap-2.5 border-y border-[var(--border)] py-3.5">
                  <div>
                    <div className="text-[11px] text-[var(--text-3)]">Return · 30d</div>
                    <div
                      className={`mt-0.5 font-mono text-[14px] font-semibold ${
                        returnPct === undefined
                          ? "text-[var(--text-2)]"
                          : returnPct >= 0
                            ? "text-[var(--pos)]"
                            : "text-[var(--neg)]"
                      }`}
                    >
                      {returnPct === undefined ? "—" : `${returnPct >= 0 ? "+" : ""}${returnPct}%`}
                    </div>
                  </div>
                  <div>
                    <div className="text-[11px] text-[var(--text-3)]">Risk</div>
                    <div className="mt-0.5 text-[13px] font-medium text-[var(--text)]">
                      {riskLabel(cr.riskProfile)}
                    </div>
                  </div>
                  <div>
                    <div className="text-[11px] text-[var(--text-3)]">Copy ratio</div>
                    <div className="mt-0.5 font-mono text-[13px] text-[var(--text)]">
                      {copyRatioLabel(cr.moneyManagementProfile)}
                    </div>
                  </div>
                </div>

                <div className="flex gap-2">
                  <CopyActionButtons id={cr.id} status={cr.status} />
                  <Link
                    href={`/copy-relationships/${cr.id}`}
                    className="flex h-[38px] items-center justify-center rounded-[9px] border border-[var(--border)] px-3 text-[12.5px] font-semibold text-[var(--text-2)] transition-colors hover:bg-[var(--surface-2)]"
                  >
                    Details
                  </Link>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
