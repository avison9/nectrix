"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import type { ConnectionRole } from "@nectrix/api-client";
import { setConnectionRoleAction } from "./actions";

const ALL_OPTIONS: { value: ConnectionRole; label: string; description: string }[] = [
  { value: "FOLLOWER_ONLY", label: "Follower only", description: "Copies trades from a Master." },
  { value: "MASTER_ONLY", label: "Master only", description: "Other accounts copy trades from this one." },
  { value: "BOTH", label: "Both", description: "Can act as either, depending on the relationship." },
];

/**
 * TICKET-101 follow-up — Master-only is off-limits unless the caller already holds the real,
 * onboarded MASTER role: broadcasting trades to OTHER people's followers requires actually being a
 * vetted Master, not just flipping a switch on any account. A Follower can never pick it, and an
 * Individual-mode caller (neither role) can only self-copy (BOTH) or follow — becoming a Master to
 * broadcast to real followers other than themselves needs the real onboarding flow, not this form.
 * Enforced server-side too (BrokerAccountService#updateBrokerAccount) — this filtering is the UX,
 * not the actual gate.
 */
export function RoleSelectionForm({
  brokerAccountId,
  initialRole,
  canBeMaster,
}: {
  brokerAccountId: string;
  initialRole: ConnectionRole;
  canBeMaster: boolean;
}) {
  const router = useRouter();
  const options = canBeMaster
    ? ALL_OPTIONS
    : ALL_OPTIONS.filter((o) => o.value !== "MASTER_ONLY");
  const [role, setRole] = useState<ConnectionRole>(
    canBeMaster || initialRole !== "MASTER_ONLY" ? initialRole : "BOTH",
  );
  const [error, setError] = useState<string | undefined>();
  const [pending, startTransition] = useTransition();

  function save() {
    setError(undefined);
    startTransition(async () => {
      const result = await setConnectionRoleAction(brokerAccountId, role);
      if ("error" in result) {
        setError(result.error);
        return;
      }
      router.push("/broker-accounts");
    });
  }

  return (
    <div className="mt-6 flex flex-col gap-2.5">
      {options.map((option) => (
        <label
          key={option.value}
          className="flex cursor-pointer items-start gap-3 rounded-[10px] border border-[var(--border)] bg-[var(--surface)] p-3"
        >
          <input
            type="radio"
            name="role"
            className="mt-0.5"
            checked={role === option.value}
            onChange={() => setRole(option.value)}
          />
          <span>
            <span className="block text-[13.5px] font-medium text-[var(--text)]">
              {option.label}
            </span>
            <span className="block text-[12px] text-[var(--text-2)]">{option.description}</span>
          </span>
        </label>
      ))}

      {error && <p className="text-[12.5px] text-[var(--neg)]">{error}</p>}

      <button
        type="button"
        onClick={save}
        disabled={pending}
        className="mt-1.5 h-[42px] rounded-[11px] bg-[var(--accent)] text-[13.5px] font-semibold text-white transition-opacity disabled:opacity-60"
      >
        {pending ? "Saving…" : "Finish"}
      </button>
    </div>
  );
}
