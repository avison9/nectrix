import Link from "next/link";
import { getCopyRelationshipAsMaster } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";
import { fetchOrNotFound } from "@/lib/fetchOrNotFound";

/**
 * Feature — replaces the previous 100%-sample-data placeholder (equity chart, sample trades) with
 * the real, scoped fields actually asked for: who they are, how long they've been following, and
 * their % return since following — deliberately never their balance/equity. Uses
 * getCopyRelationshipAsMaster (CopyRelationshipService#getCopyRelationshipForMaster), the new
 * master-side-ownership read path — a plain @PostAuthorize widening of the Follower-only
 * getCopyRelationship would have accidentally let a Master call Follower-only mutation endpoints
 * too, so this is a distinct endpoint (see that service method's own Javadoc).
 */
function durationSince(iso: string): string {
  const ms = Date.now() - new Date(iso).getTime();
  const minutes = Math.floor(ms / 60000);
  if (minutes < 60) return `${Math.max(minutes, 0)} minute${minutes === 1 ? "" : "s"}`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} hour${hours === 1 ? "" : "s"}`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days} day${days === 1 ? "" : "s"}`;
  if (days < 30) {
    const weeks = Math.floor(days / 7);
    return `${weeks} week${weeks === 1 ? "" : "s"}`;
  }
  if (days < 365) {
    const months = Math.floor(days / 30);
    return `${months} month${months === 1 ? "" : "s"}`;
  }
  const years = Math.floor(days / 365);
  return `${years} year${years === 1 ? "" : "s"}`;
}

function formatReturnPct(returnPct: number | null): string {
  if (returnPct === null) return "—";
  return `${returnPct >= 0 ? "+" : ""}${returnPct.toFixed(2)}%`;
}

export default async function MasterFollowerDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { accessToken } = await requireSession();
  const { id } = await params;
  const relationship = await fetchOrNotFound(
    getCopyRelationshipAsMaster(coreAppBaseUrl(), accessToken, id),
  );
  const isActive = relationship.status === "ACTIVE";

  return (
    <div className="mx-auto max-w-[640px]">
      <Link
        href="/master/followers"
        className="mb-4.5 inline-flex items-center gap-1.5 rounded-[9px] border border-[var(--border)] px-3 py-1.5 text-[13px] font-semibold text-[var(--text-2)] hover:bg-[var(--surface-2)]"
      >
        ← Back to followers
      </Link>

      <div className="mb-5.5 flex flex-wrap items-center gap-3.5">
        <div className="flex h-[54px] w-[54px] shrink-0 items-center justify-center rounded-full bg-[var(--accent-2)] text-[18px] font-semibold text-[var(--accent)]">
          {(relationship.followerDisplayName ?? id).slice(0, 2).toUpperCase()}
        </div>
        <div className="min-w-0 flex-1">
          <h1 className="text-[23px] font-semibold tracking-tight text-[var(--text)]">
            {relationship.followerDisplayName ?? `Follower ${id.slice(0, 8)}…`}
          </h1>
          <div className="mt-0.5 text-[13px] text-[var(--text-3)]">
            Following for {durationSince(relationship.createdAt)}
          </div>
        </div>
        <span
          className={`rounded-full px-2.5 py-1 text-[12px] font-semibold ${
            isActive
              ? "bg-[var(--pos)]/15 text-[var(--pos)]"
              : "bg-[var(--neg)]/10 text-[var(--neg)]"
          }`}
        >
          {isActive ? "Active" : "Inactive"}
        </span>
      </div>

      <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5">
        <div className="text-[12px] font-medium text-[var(--text-2)]">
          Return since following
        </div>
        <div
          className={`mt-1.5 font-mono text-[26px] font-semibold tracking-tight ${
            relationship.returnPct === null
              ? "text-[var(--text-3)]"
              : relationship.returnPct >= 0
                ? "text-[var(--pos)]"
                : "text-[var(--neg)]"
          }`}
        >
          {formatReturnPct(relationship.returnPct)}
        </div>
      </div>
    </div>
  );
}
