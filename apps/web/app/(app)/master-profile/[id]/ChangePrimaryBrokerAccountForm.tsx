"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import type { BrokerAccountSummary } from "@nectrix/api-client";
import { changePrimaryBrokerAccountAction } from "./actions";

/**
 * Bugfix — previously there was no way for a Master to change their primary broker account after
 * profile creation. Only ever renders eligible accounts the caller already filtered to (CONNECTED,
 * MASTER_ONLY/BOTH) — see this directory's page.tsx.
 */
export function ChangePrimaryBrokerAccountForm({
  masterProfileId,
  currentBrokerAccountId,
  eligibleAccounts,
}: {
  masterProfileId: string;
  currentBrokerAccountId: string;
  eligibleAccounts: BrokerAccountSummary[];
}) {
  const router = useRouter();
  const otherAccounts = eligibleAccounts.filter((a) => a.id !== currentBrokerAccountId);
  const [selected, setSelected] = useState(otherAccounts[0]?.id ?? "");
  const [error, setError] = useState<string | undefined>();
  const [saved, setSaved] = useState(false);
  const [pending, startTransition] = useTransition();

  if (otherAccounts.length === 0) {
    return null;
  }

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(undefined);
    setSaved(false);
    startTransition(async () => {
      const result = await changePrimaryBrokerAccountAction(masterProfileId, selected);
      if ("error" in result) {
        setError(result.error);
        return;
      }
      setSaved(true);
      router.refresh();
    });
  }

  return (
    <form
      onSubmit={submit}
      className="mt-6 flex flex-col gap-3 rounded-[12px] border border-[var(--border)] bg-[var(--surface)] p-4"
    >
      <span className="text-[12.5px] font-semibold text-[var(--text)]">
        Change primary broker account
      </span>
      <p className="text-[12px] text-[var(--text-2)]">
        Your primary account is the one your Followers&apos; copy relationships track. Switching it
        moves any active relationships over to the new account.
      </p>
      <div className="flex flex-wrap items-center gap-2.5">
        <select
          value={selected}
          onChange={(e) => setSelected(e.target.value)}
          className="h-9 rounded-[8px] border border-[var(--border)] bg-transparent px-2.5 text-[12.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
        >
          {otherAccounts.map((account) => (
            <option key={account.id} value={account.id}>
              {account.brokerName ?? account.brokerType} · {account.brokerAccountLogin}
            </option>
          ))}
        </select>
        <button
          type="submit"
          disabled={pending}
          className="h-9 rounded-[9px] bg-[var(--accent)] px-4 text-[12.5px] font-semibold text-white disabled:opacity-60"
        >
          {pending ? "Switching…" : "Switch"}
        </button>
      </div>
      {error && <p className="text-[12.5px] text-[var(--neg)]">{error}</p>}
      {saved && !error && <p className="text-[12.5px] text-[var(--pos)]">Switched.</p>}
    </form>
  );
}
