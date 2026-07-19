import Link from "next/link";
import { getPendingInvitation, listBrokerAccounts, listCopyRelationships } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";
import { AcceptInviteGate } from "./AcceptInviteGate";

type StepStatus = "done" | "active" | "todo";

interface Step {
  n: string;
  title: string;
  desc: string;
  status: StepStatus;
  cta?: { href: string; label: string };
  extra?: React.ReactNode;
}

/**
 * Mirrors Nectrix.dc.html's `FOLLOWER · ONBOARDING` (`vFollowerOnboarding`) as a real, nav-reachable
 * summary of flows that already exist for real (`/broker-accounts/link`, `/copy-settings`) rather
 * than a new flow of its own. Step-completion signals:
 * - "Accept your invitation" — TICKET-118: real acceptance now happens BEFORE a session even
 *   exists, at the public `/accept-invite` entry point (a Follower clicks their emailed link,
 *   accepts, and only then gets a session and lands here) — so by the time any invited Follower
 *   reaches this page, step 1 is already genuinely done. Signaled by `pendingInvitation !== null`
 *   (their account was created via an invitation and accept-invite already succeeded) OR
 *   `hasRelationship` (covers the case where step 4 below has already been completed too, after
 *   which `pendingInvitation` itself goes back to null — see InvitationCopySetupService's own
 *   Javadoc). Only a Follower with NEITHER — i.e. no invitation history at all, meaning an
 *   organic/Individual-mode path that never had a Master's invite to accept in the first place —
 *   still renders the real terms-acceptance checkbox gate (AcceptInviteGate), which stays exactly
 *   as inert as it always was for that case (no real per-master relationship exists yet to attach
 *   real terms to).
 * - "Open a broker account" derives from `listBrokerAccounts` returning at least one row (covers
 *   both opening a brand-new account via the master's IB link and linking one you already have —
 *   `/broker-accounts/link` handles both through the same form). The mock's own "I already have a
 *   broker account" checkbox (`:1469-1476` in the live design) is rendered here too, alongside the
 *   "Open account via IB link" button — both route to the same real `/broker-accounts/link` flow,
 *   since that's the only real way to attach either a brand-new or an existing account in this
 *   system; the checkbox can't mark the step "done" on its own (no way to verify the attestation
 *   without a real account row), so it stays a second real entry point into the same real action
 *   rather than a fake local-only toggle. TICKET-117 bugfix — this step used to only surface once
 *   `hasRelationship` was true, but step 1's own accept-invite gate has no real persisting endpoint
 *   yet (TICKET-118), so `hasRelationship` can never actually become true through this page's own
 *   UI — that made step 2 permanently unreachable for a real follower. Linking a broker account has
 *   no actual dependency on invitation-acceptance state, so this step is now gated purely on
 *   `hasBrokerAccount` instead.
 * - "Connect your MT5 login" is a DIFFERENT, stronger signal than step 2: MtLinkingService's own
 *   Javadoc is explicit that an MT4/MT5 account row starts `PENDING` and only flips to `CONNECTED`
 *   once a real EA session on the user's terminal actually presents its pairing token to
 *   apps/mt5-bridge-gateway — so a row existing (step 2) does NOT mean the login was verified.
 *   This step requires `connectionStatus === "CONNECTED"` specifically (cTrader OAuth accounts are
 *   verified synchronously at link time and already start CONNECTED, so this isn't MT-only).
 * - "Set your copy risk" — TICKET-118: for an invited Follower (`pendingInvitation !== null`),
 *   the CTA now goes to the real `/onboarding/start-copying` page (review the inviting Master's
 *   suggested MM/risk defaults, submit `POST /copy-relationships/from-invitation`) instead of the
 *   organic "find a master"/"copy-settings" links, which stay exactly as-is for everyone else —
 *   an invited Follower already has a specific Master to copy, no need to browse `/masters`.
 * - "Go live" derives from any relationship being `ACTIVE` — itself only reachable through real,
 *   backend-enforced transitions (risk ack + agreement sign; see CopyRelationshipService's own
 *   requireStatus guard).
 */
export default async function OnboardingPage() {
  const { accessToken } = await requireSession();
  const [accounts, relationships, pendingInvitation] = await Promise.all([
    listBrokerAccounts(coreAppBaseUrl(), accessToken),
    listCopyRelationships(coreAppBaseUrl(), accessToken, { role: "follower" }),
    getPendingInvitation(coreAppBaseUrl(), accessToken),
  ]);

  const hasBrokerAccount = accounts.length > 0;
  const isConnected = accounts.some((a) => a.connectionStatus === "CONNECTED");
  const hasRelationship = relationships.length > 0;
  const isLive = relationships.some((r) => r.status === "ACTIVE");
  const invitationAccepted = pendingInvitation !== null || hasRelationship;

  const steps: Step[] = [
    {
      n: "1",
      title: "Accept your invitation",
      desc: "Review and accept your master's terms — required before your account can be linked.",
      status: invitationAccepted ? "done" : "active",
      extra: invitationAccepted ? undefined : <AcceptInviteGate />,
    },
    {
      n: "2",
      title: "Open a broker account",
      desc: "Use your master's IB link so trades can be copied and fees settled correctly — or check the box below if you already have a broker account.",
      status: hasBrokerAccount ? "done" : "active",
      extra:
        !hasBrokerAccount ? (
          <div className="mt-3 flex flex-wrap items-center gap-3">
            <Link
              href="/broker-accounts/link"
              className="inline-flex h-[38px] items-center gap-2 rounded-[10px] bg-[var(--accent)] px-4.5 text-[13px] font-semibold text-white transition-opacity hover:opacity-90"
            >
              Open account via IB link
            </Link>
            <span className="text-[12px] text-[var(--text-3)]">or</span>
            <Link href="/broker-accounts/link" className="flex items-center gap-2.5 text-[12.5px] text-[var(--text)] hover:text-[var(--accent)]">
              <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-[6px] border border-[var(--border)]" />
              I already have a broker account
            </Link>
          </div>
        ) : undefined,
    },
    {
      n: "3",
      title: "Connect your MT5 login",
      desc: "Enter your investor password so Nectrix can mirror trades read-only.",
      status: isConnected ? "done" : hasBrokerAccount ? "active" : "todo",
      cta:
        hasBrokerAccount && !isConnected
          ? { href: "/broker-accounts", label: "Check connection status" }
          : undefined,
    },
    {
      n: "4",
      title: "Set your copy risk",
      desc: "Choose lot multiplier and drawdown limits before going live.",
      status: hasRelationship ? "done" : "todo",
      cta:
        hasBrokerAccount && !hasRelationship && pendingInvitation
          ? { href: "/onboarding/start-copying", label: "Review & start copying" }
          : hasBrokerAccount && !hasRelationship
            ? { href: "/masters", label: "Find a master to follow" }
            : hasRelationship && !isLive
              ? { href: "/copy-settings", label: "Review copy settings" }
              : undefined,
    },
    {
      n: "5",
      title: "Go live",
      desc: "Trades start copying automatically the moment your master opens a position.",
      status: isLive ? "done" : "todo",
    },
  ];

  return (
    <div className="mx-auto max-w-[720px]">
      <div className="mb-6">
        <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">Get set up</h1>
        <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
          Five steps to start copying. Already have a broker account? You can skip opening one via
          your master&apos;s IB link.
        </p>
      </div>

      <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] px-5.5">
        {steps.map((s) => (
          <div key={s.n} className="flex gap-4 border-b border-[var(--border)] py-4.5 last:border-b-0">
            <div
              className={`flex h-[30px] w-[30px] shrink-0 items-center justify-center rounded-full font-mono text-[13px] font-semibold ${
                s.status === "done"
                  ? "bg-[var(--pos)] text-white"
                  : s.status === "active"
                    ? "bg-[var(--accent)] text-white"
                    : "bg-[var(--surface-2)] text-[var(--text-3)]"
              }`}
            >
              {s.status === "done" ? (
                <svg viewBox="0 0 24 24" width={15} height={15} fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" strokeLinejoin="round">
                  <path d="M20 6L9 17l-5-5" />
                </svg>
              ) : (
                s.n
              )}
            </div>
            <div className="flex-1">
              <div className="text-[14.5px] font-semibold text-[var(--text)]">{s.title}</div>
              <div className="mt-1 text-[13px] leading-[1.5] text-[var(--text-2)]">{s.desc}</div>
              {s.cta && (
                <Link
                  href={s.cta.href}
                  className="mt-3 inline-flex h-[38px] items-center gap-2 rounded-[10px] bg-[var(--accent)] px-4.5 text-[13px] font-semibold text-white transition-opacity hover:opacity-90"
                >
                  {s.cta.label}
                </Link>
              )}
              {s.extra}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
