"use client";

import { Suspense, useEffect, useState, useTransition } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import type { ConnectionRole, CtraderAccountOption } from "@nectrix/api-client";
import { linkCtraderAccountAction, submitCallbackAction } from "./actions";

type Step = "exchanging" | "picking" | "resuming" | "error";

const ROLE_LABEL: Record<ConnectionRole, string> = {
  FOLLOWER_ONLY: "Follower — copies trades from a Master",
  MASTER_ONLY: "Master — other accounts copy trades from this one",
  BOTH: "Both — Individual mode, can act as either",
};

/**
 * Bugfix — the payload {@code confirmSelection} needs to retry {@code linkCtraderAccountAction}
 * after a 2FA-enrollment detour, without redoing the whole cTrader OAuth handshake. Deliberately
 * holds no secret: {@code linkSessionId} is an opaque, single-use Redis key
 * (OAuthLinkStateStore#createLinkSession) that BrokerAccountOAuthController's own
 * {@code requireTwoFactor} check runs BEFORE ever consuming — so it's still valid, unconsumed, and
 * safe to resubmit verbatim once 2FA is enabled, well within its 10-minute TTL.
 */
interface PendingCtraderLink {
  linkSessionId: string;
  ctidTraderAccountId: number;
  isLive: boolean;
  displayLabel: string;
  connectionRole: ConnectionRole;
  openedViaIbLinkId?: string;
  brokerName: string;
}

const PENDING_LINK_KEY = "ctrader_pending_link";

// useSearchParams() opts into client-side rendering during prerendering
// unless wrapped in a Suspense boundary -- next build fails outright
// without it (see ctrader/page.tsx's own identical Javadoc note).
function CtraderCallbackInner({ accountRole }: { accountRole: ConnectionRole }) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [step, setStep] = useState<Step>("exchanging");
  const [error, setError] = useState<string | undefined>();
  const [linkSessionId, setLinkSessionId] = useState<string | undefined>();
  const [accounts, setAccounts] = useState<CtraderAccountOption[]>([]);
  const [selected, setSelected] = useState<number | undefined>();
  const [displayLabel, setDisplayLabel] = useState("");
  const [pending, startTransition] = useTransition();

  function submitLink(input: {
    linkSessionId: string;
    ctidTraderAccountId: number;
    isLive: boolean;
    displayLabel: string;
    connectionRole: ConnectionRole;
    openedViaIbLinkId?: string;
    brokerName: string;
  }) {
    startTransition(async () => {
      const result = await linkCtraderAccountAction(input);
      if ("error" in result) {
        if (result.requiresTwoFactor) {
          window.localStorage.setItem(PENDING_LINK_KEY, JSON.stringify(input));
          router.push("/broker-accounts/2fa-required?resume=ctrader");
          return;
        }
        setError(result.error);
        setStep("error");
        return;
      }
      router.push(`/broker-accounts/symbol-mappings/${result.id}`);
    });
  }

  useEffect(() => {
    // Bugfix — a pending link resumed after the 2FA detour takes priority over the URL's own
    // ?code=, since landing back here from /broker-accounts/2fa-required carries no OAuth code at
    // all (it's a plain redirect, not cTrader's own callback) — this is the resume path, not a
    // fresh authorization round trip.
    const pendingRaw = window.localStorage.getItem(PENDING_LINK_KEY);
    if (pendingRaw) {
      window.localStorage.removeItem(PENDING_LINK_KEY);
      const pending: PendingCtraderLink = JSON.parse(pendingRaw);
      // Deferred into a promise continuation, not called synchronously in the effect body, same
      // reasoning the "missing code/state" branch below already documents.
      Promise.resolve().then(() => {
        setStep("resuming");
        submitLink(pending);
      });
      return;
    }

    const code = searchParams.get("code");
    const state = window.localStorage.getItem("ctrader_oauth_state");
    window.localStorage.removeItem("ctrader_oauth_state");
    // Deferring even the "missing code/state" case into the same .then() as the real API call
    // keeps every setState call inside an async continuation, never synchronously in the effect
    // body itself (react-hooks/set-state-in-effect).
    const resultPromise =
      !code || !state
        ? Promise.resolve({
            error: "Missing authorization code or state — please try connecting again.",
          })
        : submitCallbackAction(code, state);
    resultPromise.then((result) => {
      if ("error" in result) {
        setError(result.error);
        setStep("error");
        return;
      }
      setLinkSessionId(result.linkSessionId);
      setAccounts(result.accounts);
      setStep("picking");
    });
    // Only runs once, on mount -- re-running on searchParams identity churn would re-submit an
    // already-consumed single-use link session.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function confirmSelection() {
    if (selected === undefined || !linkSessionId) return;
    const account = accounts.find((a) => a.ctidTraderAccountId === selected);
    if (!account) return;
    setError(undefined);
    const openedViaIbLinkId =
      window.localStorage.getItem("ctrader_opened_via_ib_link_id") ?? undefined;
    window.localStorage.removeItem("ctrader_opened_via_ib_link_id");
    submitLink({
      linkSessionId,
      ctidTraderAccountId: account.ctidTraderAccountId,
      isLive: account.isLive,
      displayLabel: displayLabel || account.brokerTitleShort,
      connectionRole: accountRole,
      openedViaIbLinkId,
      brokerName: account.brokerTitleShort,
    });
  }

  return (
    <div className="mx-auto flex min-h-[60vh] max-w-[460px] flex-col justify-center">
      <h1 className="text-[20px] font-semibold tracking-tight text-[var(--text)]">
        Connect cTrader
      </h1>

      {step === "exchanging" && (
        <p className="mt-4 text-[13.5px] text-[var(--text-2)]">Finishing authorization…</p>
      )}

      {step === "resuming" && (
        <p className="mt-4 text-[13.5px] text-[var(--text-2)]">
          Resuming where you left off — linking your account…
        </p>
      )}

      {step === "error" && (
        <>
          <p className="mt-4 text-[12.5px] text-[var(--neg)]">{error}</p>
        </>
      )}

      {step === "picking" && (
        <div className="mt-6 flex flex-col gap-4">
          <p className="text-[13px] text-[var(--text-2)]">Choose the account to link:</p>
          <div className="flex flex-col gap-2">
            {accounts.map((account) => (
              <label
                key={account.ctidTraderAccountId}
                className="flex cursor-pointer items-center gap-3 rounded-[10px] border border-[var(--border)] bg-[var(--surface)] p-3"
              >
                <input
                  type="radio"
                  name="account"
                  checked={selected === account.ctidTraderAccountId}
                  onChange={() => setSelected(account.ctidTraderAccountId)}
                />
                <span className="text-[13.5px] text-[var(--text)]">
                  {account.brokerTitleShort} · {account.traderLogin} ·{" "}
                  {account.isLive ? "Live" : "Demo"}
                </span>
              </label>
            ))}
          </div>

          <label className="flex flex-col gap-1.5">
            <span className="text-[12.5px] font-medium text-[var(--text-2)]">Display label</span>
            <input
              value={displayLabel}
              onChange={(e) => setDisplayLabel(e.target.value)}
              placeholder="My cTrader account"
              className="h-10 rounded-[10px] border border-[var(--border)] bg-transparent px-3 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
            />
          </label>

          <div className="flex flex-col gap-1.5">
            <span className="text-[12.5px] font-medium text-[var(--text-2)]">Account role</span>
            <p className="rounded-[10px] border border-[var(--border)] bg-[var(--surface-2)] px-3 py-2.5 text-[12.5px] text-[var(--text-2)]">
              {ROLE_LABEL[accountRole]}
            </p>
          </div>

          {error && <p className="text-[12.5px] text-[var(--neg)]">{error}</p>}

          <button
            type="button"
            onClick={confirmSelection}
            disabled={selected === undefined || pending}
            className="h-[42px] rounded-[11px] bg-[var(--accent)] text-[13.5px] font-semibold text-white transition-opacity disabled:opacity-60"
          >
            {pending ? "Linking…" : "Link this account"}
          </button>
        </div>
      )}
    </div>
  );
}

export function CtraderCallbackClient({ accountRole }: { accountRole: ConnectionRole }) {
  return (
    <Suspense fallback={null}>
      <CtraderCallbackInner accountRole={accountRole} />
    </Suspense>
  );
}
