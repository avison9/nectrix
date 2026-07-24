"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import type { BrokerAccountSummary, FeeCollectionMethod } from "@nectrix/api-client";
import { createMasterProfileAction } from "./actions";

export function CreateMasterProfileForm({
  brokerAccounts,
}: {
  brokerAccounts: BrokerAccountSummary[];
}) {
  const router = useRouter();
  const [brokerAccountId, setBrokerAccountId] = useState(brokerAccounts[0]?.id ?? "");
  const [displayName, setDisplayName] = useState("");
  const [bio, setBio] = useState("");
  const [strategyTags, setStrategyTags] = useState("");
  const [performanceFeePercent, setPerformanceFeePercent] = useState("20");
  const [minFollowerBalance, setMinFollowerBalance] = useState("");
  const [feeCollectionMethod, setFeeCollectionMethod] =
    useState<FeeCollectionMethod>("BROKER_PARTNERSHIP");
  const [error, setError] = useState<string | undefined>();
  const [pending, startTransition] = useTransition();

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(undefined);
    startTransition(async () => {
      const result = await createMasterProfileAction({
        brokerAccountId,
        displayName,
        bio: bio || undefined,
        strategyTags: strategyTags
          .split(",")
          .map((tag) => tag.trim())
          .filter(Boolean),
        performanceFeePercent: Number(performanceFeePercent),
        feeCollectionMethod,
        minFollowerBalance: minFollowerBalance === "" ? undefined : Number(minFollowerBalance),
      });
      if ("error" in result) {
        if (result.existingProfileId) {
          router.push(`/master-profile/${result.existingProfileId}`);
          return;
        }
        setError(result.error);
        return;
      }
      router.push(`/master-profile/${result.id}`);
    });
  }

  if (brokerAccounts.length === 0) {
    return (
      <p className="mt-8 text-[13px] text-[var(--text-2)]">
        Link a broker account before creating a Master profile.
      </p>
    );
  }

  return (
    <form onSubmit={submit} className="mt-6 flex flex-col gap-3.5">
      <label className="flex flex-col gap-1.5">
        <span className="text-[12.5px] font-medium text-[var(--text-2)]">Primary broker account</span>
        <select
          value={brokerAccountId}
          onChange={(e) => setBrokerAccountId(e.target.value)}
          className="h-10 rounded-[10px] border border-[var(--border)] bg-transparent px-3 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
        >
          {brokerAccounts.map((account) => (
            <option key={account.id} value={account.id}>
              {account.displayLabel ?? account.brokerAccountLogin} · {account.brokerType}
            </option>
          ))}
        </select>
      </label>

      <label className="flex flex-col gap-1.5">
        <span className="text-[12.5px] font-medium text-[var(--text-2)]">Display name</span>
        <input
          required
          value={displayName}
          onChange={(e) => setDisplayName(e.target.value)}
          className="h-10 rounded-[10px] border border-[var(--border)] bg-transparent px-3 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
        />
      </label>

      <label className="flex flex-col gap-1.5">
        <span className="text-[12.5px] font-medium text-[var(--text-2)]">Bio</span>
        <textarea
          value={bio}
          onChange={(e) => setBio(e.target.value)}
          rows={3}
          className="rounded-[10px] border border-[var(--border)] bg-transparent px-3 py-2 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
        />
      </label>

      <label className="flex flex-col gap-1.5">
        <span className="text-[12.5px] font-medium text-[var(--text-2)]">
          Strategy tags <span className="text-[var(--text-3)]">(comma-separated)</span>
        </span>
        <input
          value={strategyTags}
          onChange={(e) => setStrategyTags(e.target.value)}
          placeholder="Scalping, EURUSD, Low drawdown"
          className="h-10 rounded-[10px] border border-[var(--border)] bg-transparent px-3 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
        />
      </label>

      <label className="flex flex-col gap-1.5">
        <span className="text-[12.5px] font-medium text-[var(--text-2)]">Performance fee %</span>
        <input
          type="number"
          min="0"
          max="100"
          step="0.01"
          required
          value={performanceFeePercent}
          onChange={(e) => setPerformanceFeePercent(e.target.value)}
          className="h-10 rounded-[10px] border border-[var(--border)] bg-transparent px-3 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
        />
      </label>

      <label className="flex flex-col gap-1.5">
        <span className="text-[12.5px] font-medium text-[var(--text-2)]">
          Minimum follower balance <span className="text-[var(--text-3)]">(optional)</span>
        </span>
        <input
          type="number"
          min="0"
          step="0.01"
          placeholder="No minimum"
          value={minFollowerBalance}
          onChange={(e) => setMinFollowerBalance(e.target.value)}
          className="h-10 rounded-[10px] border border-[var(--border)] bg-transparent px-3 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
        />
      </label>

      <label className="flex flex-col gap-1.5">
        <span className="text-[12.5px] font-medium text-[var(--text-2)]">Fee collection method</span>
        <select
          value={feeCollectionMethod}
          onChange={(e) => setFeeCollectionMethod(e.target.value as FeeCollectionMethod)}
          className="h-10 rounded-[10px] border border-[var(--border)] bg-transparent px-3 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
        >
          <option value="BROKER_PARTNERSHIP">Broker partnership</option>
          <option value="STRIPE_INVOICE">Stripe invoice</option>
        </select>
      </label>

      {error && <p className="text-[12.5px] text-[var(--neg)]">{error}</p>}

      <button
        type="submit"
        disabled={pending}
        className="mt-1.5 h-[42px] rounded-[11px] bg-[var(--accent)] text-[13.5px] font-semibold text-white transition-opacity disabled:opacity-60"
      >
        {pending ? "Creating…" : "Create Master profile"}
      </button>
    </form>
  );
}
