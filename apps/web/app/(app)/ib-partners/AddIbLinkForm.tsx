"use client";

import { useRef, useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import { createBrokerIbLinkAction } from "./actions";

export function AddIbLinkForm() {
  const router = useRouter();
  const formRef = useRef<HTMLFormElement>(null);
  const [error, setError] = useState<string | undefined>();
  const [pending, startTransition] = useTransition();

  function onSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(undefined);
    const formData = new FormData(e.currentTarget);
    startTransition(async () => {
      const result = await createBrokerIbLinkAction(null, formData);
      if ("error" in result) {
        setError(result.error);
        return;
      }
      formRef.current?.reset();
      router.refresh();
    });
  }

  return (
    <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5.5">
      <div className="mb-3 text-[14px] font-semibold text-[var(--text)]">Add an IB link</div>
      <form ref={formRef} onSubmit={onSubmit} className="flex flex-wrap gap-2.5">
        <select
          name="brokerType"
          required
          defaultValue="CTRADER"
          className="h-[42px] rounded-[11px] border border-[var(--border)] bg-transparent px-3 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
        >
          <option value="CTRADER">cTrader</option>
          <option value="MT5">MT5</option>
          <option value="MT4">MT4</option>
        </select>
        <input
          name="brokerDisplayName"
          type="text"
          required
          placeholder="Broker name (e.g. IC Markets)"
          className="h-[42px] min-w-[160px] flex-1 rounded-[11px] border border-[var(--border)] bg-transparent px-3.5 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
        />
        <input
          name="ibReferralUrlOrCode"
          type="text"
          required
          placeholder="IB referral URL or code"
          className="h-[42px] min-w-[200px] flex-[2] rounded-[11px] border border-[var(--border)] bg-transparent px-3.5 font-mono text-[13px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
        />
        <button
          type="submit"
          disabled={pending}
          className="h-[42px] rounded-[11px] bg-[var(--accent)] px-4.5 text-[13.5px] font-semibold text-white transition-colors disabled:opacity-60"
        >
          {pending ? "Adding…" : "Add link"}
        </button>
      </form>
      {error && <p className="mt-2 text-[12.5px] text-[var(--neg)]">{error}</p>}
    </div>
  );
}
