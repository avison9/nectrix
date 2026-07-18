"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import type { ConnectionRole } from "@nectrix/api-client";
import { setConnectionRoleAction } from "./actions";

const OPTIONS: { value: ConnectionRole; label: string; description: string }[] = [
  { value: "FOLLOWER_ONLY", label: "Follower only", description: "Copies trades from a Master." },
  { value: "MASTER_ONLY", label: "Master only", description: "Other accounts copy trades from this one." },
  { value: "BOTH", label: "Both", description: "Can act as either, depending on the relationship." },
];

export function RoleSelectionForm({
  brokerAccountId,
  initialRole,
}: {
  brokerAccountId: string;
  initialRole: ConnectionRole;
}) {
  const router = useRouter();
  const [role, setRole] = useState<ConnectionRole>(initialRole);
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
      {OPTIONS.map((option) => (
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
