import { listMyInvitations } from "@nectrix/api-client";
import type { Invitation } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";
import { InviteForm } from "./InviteForm";
import { RevokeButton } from "./RevokeButton";

const STATUS_LABEL: Record<Invitation["status"], string> = {
  PENDING: "Sent",
  ACCEPTED: "Accepted",
  EXPIRED: "Expired",
  REVOKED: "Revoked",
};

const STATUS_TONE: Record<Invitation["status"], string> = {
  PENDING: "bg-[var(--surface-2)] text-[var(--text-2)]",
  ACCEPTED: "bg-[var(--pos)]/15 text-[var(--pos)]",
  EXPIRED: "bg-[var(--neg)]/10 text-[var(--neg)]",
  REVOKED: "bg-[var(--neg)]/10 text-[var(--neg)]",
};

function timeAgo(iso: string): string {
  const ms = Date.now() - new Date(iso).getTime();
  const minutes = Math.floor(ms / 60000);
  if (minutes < 60) return `${Math.max(minutes, 0)}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}

/**
 * TICKET-118 — mirrors Nectrix.dc.html's `MASTER · INVITE FOLLOWERS` (`vMasterFollowers`, `:619-
 * 676`), now wired to the real invitation system. Two deliberate, disclosed deviations from the
 * mock: (1) no persistent "your invite link" block — the real backend (docs/14-api-specification.md
 * §14.13) only ever issues per-invitee, single-use tokens, not a generic reusable per-master link,
 * so that block has no real data source; (2) the pipeline's status badge is the real DB enum
 * (PENDING/ACCEPTED/EXPIRED/REVOKED) rather than the mock's own 4-stage sent/opened/funded/copying
 * — this app has no cheap way to distinguish "opened"/"funded"/"copying" without a new backend
 * query, so it shows the real, honest status instead of fabricating that granularity.
 *
 * <p>Master-only (Sidebar only ever links here for a MASTER session) — the actual enforcement is
 * server-side (`InvitationController`'s own `@PreAuthorize("hasRole('MASTER')")`), this page's own
 * role check is just the UX-level redirect-to-explanation, same pattern `analytics/page.tsx`
 * already established, rather than letting a stale tab/bookmark surface a raw 403 after a browser's
 * session cookie has since moved to a different (non-Master) account — e.g. right after using
 * `/accept-invite` in the same browser, which replaces the session cookie with the new Follower's.
 */
export default async function MasterFollowersPage() {
  const { session, accessToken } = await requireSession();

  if (!session.roles.includes("MASTER")) {
    return (
      <div className="mx-auto max-w-[480px] py-16 text-center">
        <h1 className="text-[20px] font-semibold text-[var(--text)]">Invite followers</h1>
        <p className="mt-2 text-[13.5px] text-[var(--text-2)]">
          This page is only available to Master accounts.
        </p>
      </div>
    );
  }

  const invitations = await listMyInvitations(coreAppBaseUrl(), accessToken);

  const sent = invitations.length;
  const accepted = invitations.filter((i) => i.status === "ACCEPTED").length;
  const pending = invitations.filter((i) => i.status === "PENDING").length;
  const conversion = sent > 0 ? Math.round((accepted / sent) * 100) : 0;

  const stats = [
    { label: "Invites sent", value: String(sent) },
    { label: "Accepted", value: String(accepted) },
    { label: "Pending", value: String(pending) },
    { label: "Conversion", value: `${conversion}%` },
  ];

  return (
    <div className="mx-auto max-w-[900px]">
      <div className="mb-6">
        <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">
          Invite followers
        </h1>
        <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
          Invite a follower by email. They open a broker account, then start copying automatically
          once they accept.
        </p>
      </div>

      <div className="mb-4 grid grid-cols-1 gap-4 lg:grid-cols-[1.4fr_1fr]">
        <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5.5">
          <InviteForm />
        </div>

        <div className="grid grid-cols-2 gap-3">
          {stats.map((s) => (
            <div
              key={s.label}
              className="rounded-[14px] border border-[var(--border)] bg-[var(--surface)] p-4"
            >
              <div className="text-[12px] font-medium text-[var(--text-2)]">{s.label}</div>
              <div className="mt-2 font-mono text-[24px] font-semibold tracking-tight text-[var(--text)]">
                {s.value}
              </div>
            </div>
          ))}
        </div>
      </div>

      <div className="overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)]">
        <div className="border-b border-[var(--border)] px-5 py-3.5 text-[14px] font-semibold text-[var(--text)]">
          Invite pipeline
        </div>
        {invitations.length === 0 ? (
          <div className="px-5 py-6 text-[13px] text-[var(--text-2)]">
            No invitations sent yet — use the form above to invite your first follower.
          </div>
        ) : (
          <div className="flex flex-col">
            {invitations.map((inv) => (
              <div
                key={inv.id}
                className="flex items-center gap-3.5 border-t border-[var(--border)] px-5 py-3.5 first:border-t-0"
              >
                <span className="min-w-0 flex-1 truncate font-mono text-[13px] text-[var(--text)]">
                  {inv.invitedEmail}
                </span>
                <span className="whitespace-nowrap text-[12.5px] text-[var(--text-3)]">
                  {timeAgo(inv.createdAt)}
                </span>
                <span
                  className={`rounded-full px-2.5 py-1 text-[12px] font-semibold whitespace-nowrap ${STATUS_TONE[inv.status]}`}
                >
                  {STATUS_LABEL[inv.status]}
                </span>
                {inv.status === "PENDING" && <RevokeButton id={inv.id} />}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
