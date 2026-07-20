import { listMyProspectNominations } from "@nectrix/api-client";
import type { MyProspectNomination } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";
import { NominateForm } from "./NominateForm";
import { ReferralsToggle } from "./ReferralsToggle";

const STATUS_LABEL: Record<MyProspectNomination["status"], string> = {
  SENT: "Sent",
  INVITED: "Invited",
  JOINED: "Joined",
  DISMISSED: "Dismissed",
};

// Badge-style status pills, same bg/15 + text convention as /master/followers and
// /follower/commission — green for a real success signal, grey for still-pending, red for a
// terminal negative outcome. JOINED/DISMISSED map onto the same semantics the earnings table's
// Paid/Pending/Declined statuses use below.
const STATUS_TONE: Record<MyProspectNomination["status"], string> = {
  SENT: "bg-[var(--surface-2)] text-[var(--text-3)]",
  INVITED: "bg-[var(--accent-2)] text-[var(--accent)]",
  JOINED: "bg-[var(--pos)]/15 text-[var(--pos)]",
  DISMISSED: "bg-[var(--neg)]/15 text-[var(--neg)]",
};

type EarningStatus = "Paid" | "Pending" | "Declined";

const EARNING_STATUS_TONE: Record<EarningStatus, string> = {
  Paid: "bg-[var(--pos)]/15 text-[var(--pos)]",
  Pending: "bg-[var(--surface-2)] text-[var(--text-3)]",
  Declined: "bg-[var(--neg)]/15 text-[var(--neg)]",
};

// Still placeholder — no reward-computation/payout system exists anywhere (genuine Phase 2/
// TICKET-207 gap), same honest-disclosure reasoning this page already established.
const SAMPLE_EARNING_STATS = [
  { label: "Earned this month", value: "$38" },
  { label: "Rebate rate", value: "3.0%" },
];

const SAMPLE_EARNING_ROWS: {
  email: string;
  date: string;
  rebate: string;
  status: EarningStatus;
}[] = [
  { email: "chris.yang@email.com", date: "Jun 12", rebate: "+$22", status: "Paid" },
  { email: "n.farouk@email.com", date: "May 30", rebate: "+$16", status: "Paid" },
  { email: "sam.lowe@email.com", date: "May 18", rebate: "$0", status: "Pending" },
  { email: "j.reyes@email.com", date: "May 2", rebate: "$0", status: "Declined" },
];

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

/**
 * Mirrors Nectrix.dc.html's `FOLLOWER · REFERRALS` (`vFollowerReferral`, `:1517-1565`). TICKET-118
 * follow-up — the "Refer a trader by email" form and the "Your referrals" tab (status/stats) are
 * real (`ProspectNomination` data, `JOINED` reflecting the linked invitation's actual acceptance —
 * see `ProspectNominationService#listForFollower`'s own Javadoc). The "Referral earnings" tab
 * stays a disclosed placeholder — no reward-computation/payout system exists anywhere yet (genuine
 * Phase 2/TICKET-207 gap) — kept alongside the real tab rather than replaced by it, since it's real
 * mock content the user asked to keep visible.
 */
export default async function FollowerReferralsPage() {
  const { session, accessToken } = await requireSession();

  if (!session.roles.includes("FOLLOWER")) {
    return (
      <div className="mx-auto max-w-[480px] py-16 text-center">
        <h1 className="text-[20px] font-semibold text-[var(--text)]">Refer &amp; earn</h1>
        <p className="mt-2 text-[13.5px] text-[var(--text-2)]">
          This page is only available to Follower accounts.
        </p>
      </div>
    );
  }

  const nominations = await listMyProspectNominations(coreAppBaseUrl(), accessToken);
  const sent = nominations.length;
  const joined = nominations.filter((n) => n.status === "JOINED").length;

  const mineContent = (
    <>
      <div className="mb-4 grid grid-cols-1 gap-3.5 sm:grid-cols-2">
        <div className="rounded-[14px] border border-[var(--border)] bg-[var(--surface)] p-4">
          <div className="text-[12px] font-medium text-[var(--text-2)]">Referrals sent</div>
          <div className="mt-2 font-mono text-[24px] font-semibold tracking-tight text-[var(--text)]">
            {sent}
          </div>
        </div>
        <div className="rounded-[14px] border border-[var(--border)] bg-[var(--surface)] p-4">
          <div className="text-[12px] font-medium text-[var(--text-2)]">Joined</div>
          <div className="mt-2 font-mono text-[24px] font-semibold tracking-tight text-[var(--text)]">
            {joined}
          </div>
        </div>
      </div>

      <div className="overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)]">
        <div className="border-b border-[var(--border)] px-5 py-3.5 text-[14px] font-semibold text-[var(--text)]">
          Your referrals
        </div>
        {nominations.length === 0 ? (
          <div className="px-5 py-6 text-[13px] text-[var(--text-2)]">
            No referrals sent yet — use the form above to refer your first trader.
          </div>
        ) : (
          <div className="flex flex-col">
            {nominations.map((n) => (
              <div
                key={n.id}
                className="flex items-center gap-3.5 border-t border-[var(--border)] px-5 py-3.5 first:border-t-0"
              >
                <span className="min-w-0 flex-1 truncate font-mono text-[13px] text-[var(--text)]">
                  {n.prospectEmail}
                </span>
                <span className="whitespace-nowrap text-[12.5px] text-[var(--text-3)]">
                  {formatDate(n.createdAt)}
                </span>
                <span
                  className={`rounded-full px-2.5 py-1 text-[12px] font-semibold whitespace-nowrap ${STATUS_TONE[n.status]}`}
                >
                  {STATUS_LABEL[n.status]}
                </span>
              </div>
            ))}
          </div>
        )}
      </div>
    </>
  );

  const earningsContent = (
    <>
      <p
        className="mb-4 text-[12.5px] text-[var(--text-3)]"
        title="Reward/rebate computation is Phase 2 (TICKET-207) — not built yet"
      >
        Showing sample data — reward/rebate tracking is a Phase 2 feature and isn&apos;t wired up
        yet.
      </p>
      <div className="mb-4 grid grid-cols-1 gap-3.5 sm:grid-cols-2 lg:grid-cols-[1fr_1fr_1.2fr]">
        {SAMPLE_EARNING_STATS.map((s) => (
          <div
            key={s.label}
            className="rounded-[14px] border border-[var(--border)] bg-[var(--surface)] p-4 opacity-70"
          >
            <div className="text-[12px] font-medium text-[var(--text-2)]">{s.label}</div>
            <div className="mt-2 font-mono text-[24px] font-semibold tracking-tight text-[var(--text)]">
              {s.value}
            </div>
          </div>
        ))}
        <div className="rounded-[14px] bg-[var(--accent-2)] p-4 opacity-70">
          <div className="text-[12px] font-semibold text-[var(--accent)]">Your rebate rate</div>
          <div className="mt-2 font-mono text-[24px] font-semibold tracking-tight text-[var(--accent)]">
            3.0%
          </div>
        </div>
      </div>

      <div className="overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)] opacity-70">
        <div className="border-b border-[var(--border)] px-5 py-3.5 text-[14px] font-semibold text-[var(--text)]">
          Referral earnings
        </div>
        <div className="flex flex-col">
          {SAMPLE_EARNING_ROWS.map((r) => (
            <div
              key={r.email}
              className="flex items-center gap-3.5 border-t border-[var(--border)] px-5 py-3.5 first:border-t-0"
            >
              <span className="min-w-0 flex-1 truncate font-mono text-[13px] text-[var(--text)]">
                {r.email}
              </span>
              <span className="whitespace-nowrap text-[12.5px] text-[var(--text-3)]">{r.date}</span>
              <span className="w-16 text-right font-mono text-[13px] font-semibold text-[var(--text)]">
                {r.rebate}
              </span>
              <span
                className={`w-20 rounded-full px-2.5 py-1 text-center text-[12px] font-semibold whitespace-nowrap ${EARNING_STATUS_TONE[r.status]}`}
              >
                {r.status}
              </span>
            </div>
          ))}
        </div>
      </div>
    </>
  );

  return (
    <div className="mx-auto max-w-[1000px]">
      <div className="mb-6">
        <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">Refer &amp; earn</h1>
        <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
          Refer other traders and earn a rebate on every fee their account generates. Submit a
          prospect&apos;s email — your master sends the official invite.
        </p>
      </div>

      <div className="mb-4 rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5.5">
        <div className="mb-3 text-[13px] font-medium text-[var(--text-2)]">
          Refer a trader by email
        </div>
        <NominateForm />
        <p className="mt-3.5 rounded-[11px] bg-[var(--surface-2)] p-3 text-[12.5px] leading-[1.5] text-[var(--text-2)]">
          The email goes to your master to send the invite. Reward crediting once the prospect
          joins and funds isn&apos;t wired up yet (Phase 2).
        </p>
      </div>

      <ReferralsToggle mineContent={mineContent} earningsContent={earningsContent} />
    </div>
  );
}
