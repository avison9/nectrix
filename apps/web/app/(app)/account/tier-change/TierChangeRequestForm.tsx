"use client";

import { useRef, useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import type { TierChangeTargetRole } from "@nectrix/api-client";
import { submitTierChangeRequestAction } from "./actions";

const AGREEMENT_TEXT: Record<TierChangeTargetRole, { title: string; body: string }> = {
  MASTER: {
    title: "Master Trading Agreement",
    body:
      "As a Master, your trading activity becomes visible to Followers who choose to copy it, and " +
      "you take on the responsibilities that come with managing other traders' capital exposure — " +
      "accurate broker linking, timely disclosure of risk, and compliance with Nectrix's Master " +
      "conduct standards.",
  },
  FOLLOWER: {
    title: "Follower Risk Disclosure",
    body:
      "As a Follower, trades placed by any Master you choose to copy will be replicated on your own " +
      "linked broker account, at your own risk. Copy trading can result in losses that mirror or " +
      "exceed the Master's own — you should only copy Masters and risk settings you understand.",
  },
};

export function TierChangeRequestForm() {
  const router = useRouter();
  const formRef = useRef<HTMLFormElement>(null);
  const [targetMode, setTargetMode] = useState<TierChangeTargetRole>("MASTER");
  const [error, setError] = useState<string | undefined>();
  const [pending, startTransition] = useTransition();
  const agreement = AGREEMENT_TEXT[targetMode];

  function onSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(undefined);
    const formData = new FormData(e.currentTarget);
    startTransition(async () => {
      const result = await submitTierChangeRequestAction(null, formData);
      if ("error" in result) {
        setError(result.error);
        return;
      }
      formRef.current?.reset();
      router.refresh();
    });
  }

  return (
    <form
      ref={formRef}
      onSubmit={onSubmit}
      className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5.5"
    >
      <div className="mb-1 text-[14px] font-semibold text-[var(--text)]">Request account tier change</div>
      <p className="mb-3.5 text-[12.5px] text-[var(--text-2)]">
        Choose the tier you&rsquo;d like to switch to. An Admin reviews every request before it
        takes effect.
      </p>

      <div className="mb-3.5 flex gap-2">
        {(["MASTER", "FOLLOWER"] as const).map((mode) => (
          <label
            key={mode}
            className={`flex-1 cursor-pointer rounded-[11px] border px-4 py-3 text-[13.5px] font-medium transition-colors ${
              targetMode === mode
                ? "border-[var(--accent)] bg-[var(--accent)]/8 text-[var(--text)]"
                : "border-[var(--border)] text-[var(--text-2)] hover:bg-[var(--surface-2)]"
            }`}
          >
            <input
              type="radio"
              name="targetMode"
              value={mode}
              checked={targetMode === mode}
              onChange={() => setTargetMode(mode)}
              className="sr-only"
            />
            {mode === "MASTER" ? "Become a Master" : "Become a Follower"}
          </label>
        ))}
      </div>

      <div className="mb-3.5 rounded-[11px] border border-[var(--border)] bg-[var(--surface-2)] p-3.5">
        <div className="mb-1 text-[12.5px] font-semibold text-[var(--text)]">{agreement.title}</div>
        <p className="text-[12px] leading-relaxed text-[var(--text-2)]">{agreement.body}</p>
      </div>

      <label className="mb-3.5 flex items-start gap-2 text-[12.5px] text-[var(--text-2)]">
        <input
          type="checkbox"
          name="agreementAccepted"
          required
          className="mt-0.5 h-4 w-4 rounded border-[var(--border)] accent-[var(--accent)]"
        />
        I have read and accept the {agreement.title}.
      </label>

      <button
        type="submit"
        disabled={pending}
        className="h-[42px] rounded-[11px] bg-[var(--accent)] px-4.5 text-[13.5px] font-semibold text-white transition-colors disabled:opacity-60"
      >
        {pending ? "Submitting…" : "Submit request"}
      </button>
      {error && <p className="mt-2 text-[12.5px] text-[var(--neg)]">{error}</p>}
    </form>
  );
}
