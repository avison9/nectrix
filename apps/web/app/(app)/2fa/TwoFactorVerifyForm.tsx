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
    <form onSubmit={submit} className="flex flex-col gap-3">
      <input
        value={code}
        onChange={(e) => setCode(e.target.value)}
        type="text"
        required
        autoFocus
        inputMode="numeric"
        autoComplete="one-time-code"
        maxLength={6}
        placeholder="482 913"
        className="h-11 w-40 rounded-[11px] border border-[var(--border)] bg-transparent px-3.5 font-mono text-[16px] tracking-[.06em] text-[var(--text)] outline-none focus:border-[var(--accent)]"
      />

      {error && <p className="text-[12.5px] text-[var(--neg)]">{error}</p>}

      <button
        type="submit"
        disabled={pending || code.length !== 6}
        className="h-[46px] w-fit rounded-[11px] bg-[var(--accent)] px-5.5 text-[14px] font-semibold text-white transition-opacity disabled:opacity-60"
      >
        {pending ? "Verifying…" : "Verify & enable"}
      </button>
    </form>
  );
}
