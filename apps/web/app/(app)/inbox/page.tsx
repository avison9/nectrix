import { listProspectNominations } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";
import { NominationActions } from "./NominationActions";

function timeAgo(iso: string): string {
  const ms = Date.now() - new Date(iso).getTime();
  const minutes = Math.floor(ms / 60000);
  if (minutes < 60) return `${Math.max(minutes, 0)}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}

function initials(name: string | null): string {
  if (!name) return "?";
  const parts = name.trim().split(/\s+/);
  return parts
    .slice(0, 2)
    .map((p) => p[0]?.toUpperCase() ?? "")
    .join("");
}

/**
 * TICKET-118 follow-up — mirrors Nectrix.dc.html's `MASTER · INBOX` (`vMasterInbox`, `:784-859`),
 * now wired to the real `prospect_nominations` flow (see `ProspectNominationService`'s own
 * Javadoc). One deliberate, disclosed deviation from the mock: no separate "prospect name"/"note"
 * fields are shown — the real `/follower/referrals` form (this inbox's only real data source)
 * only ever collects the prospect's email, so there's nothing else honest to render.
 */
export default async function MasterInboxPage() {
  const { session, accessToken } = await requireSession();

  if (!session.roles.includes("MASTER")) {
    return (
      <div className="mx-auto max-w-[480px] py-16 text-center">
        <h1 className="text-[20px] font-semibold text-[var(--text)]">Inbox</h1>
        <p className="mt-2 text-[13.5px] text-[var(--text-2)]">
          This page is only available to Master accounts.
        </p>
      </div>
    );
  }

  const nominations = await listProspectNominations(coreAppBaseUrl(), accessToken, "PENDING");

  return (
    <div className="mx-auto max-w-[900px]">
      <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">Inbox</h1>
          <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
            Referral requests from your followers. When a follower nominates a prospect, send them an
            official invite to link an account.
          </p>
        </div>
        <span className="rounded-full bg-[var(--accent-2)] px-3 py-1.5 text-[12.5px] font-semibold text-[var(--accent)]">
          {nominations.length} awaiting invite
        </span>
      </div>

      {nominations.length === 0 ? (
        <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-6 text-center text-[13.5px] text-[var(--text-2)]">
          No pending referrals — your followers can nominate a prospect from their own Referrals
          page.
        </div>
      ) : (
        <div className="flex flex-col gap-3">
          {nominations.map((n) => (
            <div
              key={n.id}
              className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-4.5"
            >
              <div className="flex items-start gap-3.5">
                <div className="flex h-[38px] w-[38px] shrink-0 items-center justify-center rounded-full bg-[var(--accent-2)] text-[13px] font-semibold text-[var(--accent)]">
                  {initials(n.nominatedByDisplayName)}
                </div>
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-[14px] font-semibold text-[var(--text)]">
                      {n.nominatedByDisplayName ?? "A follower"}
                    </span>
                    <span className="text-[12.5px] text-[var(--text-3)]">
                      referred a prospect · {timeAgo(n.createdAt)}
                    </span>
                  </div>
                  <div className="mt-3 rounded-[11px] bg-[var(--surface-2)] p-3.5">
                    <span className="font-mono text-[13px] text-[var(--text)]">{n.prospectEmail}</span>
                  </div>
                  <NominationActions nominationId={n.id} prospectEmail={n.prospectEmail} />
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
