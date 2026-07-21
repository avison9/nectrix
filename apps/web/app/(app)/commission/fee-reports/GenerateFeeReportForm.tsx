"use client";

import { useRef, useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import { generateBrokerFeeReportAction } from "./actions";

function isoDate(daysAgo: number): string {
  const d = new Date();
  d.setDate(d.getDate() - daysAgo);
  return d.toISOString().slice(0, 10);
}

export function GenerateFeeReportForm() {
  const router = useRouter();
  const formRef = useRef<HTMLFormElement>(null);
  const [error, setError] = useState<string | undefined>();
  const [pending, startTransition] = useTransition();

  function onSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(undefined);
    const formData = new FormData(e.currentTarget);
    startTransition(async () => {
      const result = await generateBrokerFeeReportAction(null, formData);
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
      <div className="mb-1 text-[14px] font-semibold text-[var(--text)]">Generate a broker fee report</div>
      <p className="mb-3 text-[12.5px] text-[var(--text-2)]">
        Bundles every pending performance fee for your Broker Partnership relationships with the
        selected broker into one report you can send.
      </p>
      <form ref={formRef} onSubmit={onSubmit} className="flex flex-wrap items-end gap-2.5">
        <div className="flex flex-col gap-1">
          <label className="text-[11px] font-medium text-[var(--text-2)]">Broker</label>
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
        </div>
        <div className="flex flex-col gap-1">
          <label className="text-[11px] font-medium text-[var(--text-2)]">Period start</label>
          <input
            name="periodStart"
            type="date"
            required
            defaultValue={isoDate(30)}
            className="h-[42px] rounded-[11px] border border-[var(--border)] bg-transparent px-3 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          />
        </div>
        <div className="flex flex-col gap-1">
          <label className="text-[11px] font-medium text-[var(--text-2)]">Period end</label>
          <input
            name="periodEnd"
            type="date"
            required
            defaultValue={isoDate(0)}
            className="h-[42px] rounded-[11px] border border-[var(--border)] bg-transparent px-3 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          />
        </div>
        <button
          type="submit"
          disabled={pending}
          className="h-[42px] rounded-[11px] bg-[var(--accent)] px-4.5 text-[13.5px] font-semibold text-white transition-colors disabled:opacity-60"
        >
          {pending ? "Generating…" : "Generate report"}
        </button>
      </form>
      {error && <p className="mt-2 text-[12.5px] text-[var(--neg)]">{error}</p>}
    </div>
  );
}
