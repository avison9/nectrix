import Link from "next/link";
import { listBrokerAccounts, listCopyRelationships } from "@nectrix/api-client";
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
 * - "Accept your invitation" derives from having at least one copy relationship — the domain model
 *   requires an accepted follow_request before a copy_relationship can exist at all, so a
 *   relationship's presence is the closest real signal to "invitation accepted" available. While
 *   not yet accepted, renders the real terms-acceptance checkbox gate (AcceptInviteGate) — that UI
 *   is interactive but doesn't persist anywhere yet (TICKET-118 hasn't shipped a real endpoint).
 * - "Open a broker account" derives from `listBrokerAccounts` returning at least one row (covers
 *   both opening a brand-new account via the master's IB link and linking one you already have —
 *   `/broker-accounts/link` handles both through the same form; the mock's separate "I already
 *   have a broker account" checkbox has no real equivalent action in our system since either path
 *   converges on the same real linking flow).
 * - "Connect your MT5 login" is a DIFFERENT, stronger signal than step 2: MtLinkingService's own
 *   Javadoc is explicit that an MT4/MT5 account row starts `PENDING` and only flips to `CONNECTED`
 *   once a real EA session on the user's terminal actually presents its pairing token to
 *   apps/mt5-bridge-gateway — so a row existing (step 2) does NOT mean the login was verified.
 *   This step requires `connectionStatus === "CONNECTED"` specifically (cTrader OAuth accounts are
 *   verified synchronously at link time and already start CONNECTED, so this isn't MT-only).
 * - "Set your copy risk" derives from having at least one copy relationship (creating one already
 *   requires money-management/risk settings, defaults or chosen).
 * - "Go live" derives from any relationship being `ACTIVE` — itself only reachable through real,
 *   backend-enforced transitions (risk ack + agreement sign; see CopyRelationshipService's own
 *   requireStatus guard).
 */
export default async function OnboardingPage() {
  const { accessToken } = await requireSession();
  const [accounts, relationships] = await Promise.all([
    listBrokerAccounts(coreAppBaseUrl(), accessToken),
    listCopyRelationships(coreAppBaseUrl(), accessToken, { role: "follower" }),
  ]);

  const hasBrokerAccount = accounts.length > 0;
  const isConnected = accounts.some((a) => a.connectionStatus === "CONNECTED");
  const hasRelationship = relationships.length > 0;
  const isLive = relationships.some((r) => r.status === "ACTIVE");

  const steps: Step[] = [
    {
      n: "1",
      title: "Accept your invitation",
      desc: "Review and accept your master's terms — required before your account can be linked.",
      status: hasRelationship ? "done" : "active",
      extra: hasRelationship ? undefined : <AcceptInviteGate />,
    },
    {
      n: "2",
      title: "Open a broker account",
      desc: "Use your master's IB link so trades can be copied and fees settled correctly — or link an account you already have. Either way works.",
      status: hasBrokerAccount ? "done" : hasRelationship ? "active" : "todo",
      cta:
        hasRelationship && !hasBrokerAccount
          ? { href: "/broker-accounts/link", label: "Link a broker account" }
          : undefined,
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
        hasBrokerAccount && !hasRelationship
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
