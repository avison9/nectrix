"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { verifyTwoFactorAction } from "./actions";

export function TwoFactorVerifyForm() {
  const router = useRouter();
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | undefined>();
  const [pending, startTransition] = useTransition();

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(undefined);
    startTransition(async () => {
      const result = await verifyTwoFactorAction(code);
      if (result.error) {
        setError(result.error);
        return;
      }
      router.push("/profile");
    });
  }

  return (
    <form onSubmit={submit} className="mt-6 flex flex-col gap-3.5">
      <label className="flex flex-col gap-1.5">
        <span className="text-[12.5px] font-medium text-[var(--text-2)]">
          Enter the 6-digit code from your app
        </span>
        <input
          value={code}
          onChange={(e) => setCode(e.target.value)}
          type="text"
          required
          autoFocus
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={6}
          className="h-10 rounded-[10px] border border-[var(--border)] bg-transparent px-3 font-mono text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
        />
      </label>

      {error && <p className="text-[12.5px] text-[var(--neg)]">{error}</p>}

      <button
        type="submit"
        disabled={pending || code.length !== 6}
        className="h-[42px] rounded-[11px] bg-[var(--accent)] text-[13.5px] font-semibold text-white transition-opacity disabled:opacity-60"
      >
        {pending ? "Verifying…" : "Enable two-factor authentication"}
      </button>
    </form>
  );
}
