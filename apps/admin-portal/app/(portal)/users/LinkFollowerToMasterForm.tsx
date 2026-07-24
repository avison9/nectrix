"use client";

import { useState, useTransition } from "react";
import type { BrokerAccountSummary } from "@nectrix/api-client";
import { findMasterBrokerAccountsByEmailAction, linkFollowerToMasterAction } from "./actions";

/**
 * #421 + TICKET-125 — lets an ADMIN/SUPER_ADMIN link this Follower directly to a Master's specific
 * broker account/strategy, without an invite/follow-request. A Master may own more than one
 * eligible (MASTER_ONLY/BOTH) account, one per strategy — so this is now a two-step flow: look up
 * the Master by email, then pick which of their accounts to link to. In-page confirm/error state,
 * same pattern UserActions.tsx already establishes (not window.confirm — see that component's own
 * Javadoc for why).
 */
export function LinkFollowerToMasterForm({
  followerId,
  brokerAccounts,
}: {
  followerId: string;
  brokerAccounts: BrokerAccountSummary[];
}) {
  const [masterEmail, setMasterEmail] = useState("");
  const [masterAccounts, setMasterAccounts] = useState<BrokerAccountSummary[] | undefined>();
  const [masterBrokerAccountId, setMasterBrokerAccountId] = useState("");
  const [brokerAccountId, setBrokerAccountId] = useState(brokerAccounts[0]?.id ?? "");
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<string | undefined>();
  const [success, setSuccess] = useState<string | undefined>();
  const [pending, startTransition] = useTransition();

  if (brokerAccounts.length === 0) {
    return (
      <p className="text-[13px] text-[var(--text-2)]">
        This user has no linked broker account to copy through yet.
      </p>
    );
  }

  function onFindMaster() {
    setError(undefined);
    setSuccess(undefined);
    setMasterAccounts(undefined);
    startTransition(async () => {
      const result = await findMasterBrokerAccountsByEmailAction(masterEmail);
      if (result.error || !result.accounts) {
        setError(result.error ?? "Something went wrong.");
        return;
      }
      setMasterAccounts(result.accounts);
      setMasterBrokerAccountId(result.accounts[0]?.id ?? "");
    });
  }

  function onSubmit() {
    setError(undefined);
    setSuccess(undefined);
    setConfirming(false);
    startTransition(async () => {
      const result = await linkFollowerToMasterAction(
        followerId,
        masterBrokerAccountId,
        brokerAccountId,
      );
      if (result.error || !result.result) {
        setError(result.error ?? "Something went wrong.");
        return;
      }
      setSuccess(
        `Linked to ${result.result.masterDisplayName} — status ${result.result.status}.`,
      );
      setMasterEmail("");
      setMasterAccounts(undefined);
      setMasterBrokerAccountId("");
    });
  }

  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-2">
        <input
          type="email"
          placeholder="Master's email"
          value={masterEmail}
          onChange={(e) => {
            setMasterEmail(e.target.value);
            setMasterAccounts(undefined);
          }}
          disabled={pending}
          className="h-9 w-64 rounded-[9px] border border-[var(--border)] bg-[var(--surface)] px-3 text-[12.5px] text-[var(--text)] disabled:opacity-60"
        />
        {!masterAccounts && (
          <button
            type="button"
            disabled={pending || !masterEmail}
            onClick={onFindMaster}
            className="h-9 rounded-[9px] border border-[var(--border)] px-3.5 text-[12.5px] font-semibold text-[var(--text)] transition-colors hover:bg-[var(--surface-2)] disabled:opacity-60"
          >
            {pending ? "Looking up…" : "Find Master"}
          </button>
        )}
        {masterAccounts && (
          <select
            value={masterBrokerAccountId}
            onChange={(e) => setMasterBrokerAccountId(e.target.value)}
            disabled={pending}
            className="h-9 rounded-[9px] border border-[var(--border)] bg-[var(--surface)] px-3 text-[12.5px] text-[var(--text)] disabled:opacity-60"
          >
            {masterAccounts.map((account) => (
              <option key={account.id} value={account.id}>
                {account.brokerName ?? account.brokerType} · {account.brokerAccountLogin}
              </option>
            ))}
          </select>
        )}
        <select
          value={brokerAccountId}
          onChange={(e) => setBrokerAccountId(e.target.value)}
          disabled={pending}
          className="h-9 rounded-[9px] border border-[var(--border)] bg-[var(--surface)] px-3 text-[12.5px] text-[var(--text)] disabled:opacity-60"
        >
          {brokerAccounts.map((account) => (
            <option key={account.id} value={account.id}>
              {account.brokerName ?? account.brokerType} · {account.brokerAccountLogin}
            </option>
          ))}
        </select>
        {masterAccounts &&
          (confirming ? (
            <>
              <span className="text-[12px] text-[var(--text-2)]">Link now?</span>
              <button
                type="button"
                disabled={pending}
                onClick={onSubmit}
                className="h-9 rounded-[9px] border border-[var(--border)] px-3 text-[12.5px] font-semibold text-[var(--pos)] transition-colors hover:bg-[var(--pos)]/8 disabled:opacity-60"
              >
                {pending ? "Linking…" : "Yes"}
              </button>
              <button
                type="button"
                disabled={pending}
                onClick={() => setConfirming(false)}
                className="h-9 rounded-[9px] border border-[var(--border)] px-3 text-[12.5px] font-semibold text-[var(--text-2)] transition-colors hover:bg-[var(--surface-2)] disabled:opacity-60"
              >
                Cancel
              </button>
            </>
          ) : (
            <button
              type="button"
              disabled={pending || !masterBrokerAccountId}
              onClick={() => setConfirming(true)}
              className="h-9 rounded-[9px] border border-[var(--border)] px-3.5 text-[12.5px] font-semibold text-[var(--text)] transition-colors hover:bg-[var(--surface-2)] disabled:opacity-60"
            >
              Link to Master
            </button>
          ))}
      </div>
      {error && <p className="text-[11.5px] text-[var(--neg)]">{error}</p>}
      {success && <p className="text-[11.5px] text-[var(--pos)]">{success}</p>}
    </div>
  );
}
