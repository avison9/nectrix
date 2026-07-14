"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useState, useTransition } from "react";
import type { ConnectionRole, CtraderAccountOption } from "@nectrix/api-client";
import { linkCtraderAccountAction, submitCallbackAction } from "./actions";

type Step = "exchanging" | "picking" | "error";

export default function CtraderCallbackPage() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [step, setStep] = useState<Step>("exchanging");
  const [error, setError] = useState<string | undefined>();
  const [linkSessionId, setLinkSessionId] = useState<string | undefined>();
  const [accounts, setAccounts] = useState<CtraderAccountOption[]>([]);
  const [selected, setSelected] = useState<number | undefined>();
  const [displayLabel, setDisplayLabel] = useState("");
  const [connectionRole, setConnectionRole] = useState<ConnectionRole>("FOLLOWER_ONLY");
  const [pending, startTransition] = useTransition();

  useEffect(() => {
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
    startTransition(async () => {
      const openedViaIbLinkId =
        window.localStorage.getItem("ctrader_opened_via_ib_link_id") ?? undefined;
      window.localStorage.removeItem("ctrader_opened_via_ib_link_id");
      const result = await linkCtraderAccountAction({
        linkSessionId,
        ctidTraderAccountId: account.ctidTraderAccountId,
        isLive: account.isLive,
        displayLabel: displayLabel || account.brokerTitleShort,
        connectionRole,
        openedViaIbLinkId,
      });
      if ("error" in result) {
        if (result.requiresTwoFactor) {
          router.push("/broker-accounts/2fa-required");
          return;
        }
        setError(result.error);
        return;
      }
      router.push(`/broker-accounts/symbol-mappings/${result.id}`);
    });
  }

  return (
    <main className="mx-auto flex min-h-screen max-w-[460px] flex-col justify-center px-4 py-10">
      <h1 className="text-[20px] font-semibold tracking-tight text-[var(--text)]">
        Connect cTrader
      </h1>

      {step === "exchanging" && (
        <p className="mt-4 text-[13.5px] text-[var(--text-2)]">Finishing authorization…</p>
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

          <label className="flex flex-col gap-1.5">
            <span className="text-[12.5px] font-medium text-[var(--text-2)]">Account role</span>
            <select
              value={connectionRole}
              onChange={(e) => setConnectionRole(e.target.value as ConnectionRole)}
              className="h-10 rounded-[10px] border border-[var(--border)] bg-transparent px-3 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
            >
              <option value="FOLLOWER_ONLY">Follower only</option>
              <option value="MASTER_ONLY">Master only</option>
              <option value="BOTH">Both</option>
            </select>
          </label>

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
    </main>
  );
}
