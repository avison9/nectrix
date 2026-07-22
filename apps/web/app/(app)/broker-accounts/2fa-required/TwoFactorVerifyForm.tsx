"use client";

import { Suspense, useState, useTransition } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { verifyTwoFactorAction } from "./actions";

/**
 * Bugfix — {@code resume} names which in-progress broker link (its own page already stashed the
 * non-secret parts of its state in localStorage before redirecting here, see
 * CtraderCallbackClient/Mt5LinkClient's own Javadoc) to send the user back to, instead of always
 * landing on the generic picker and losing that progress. Absent/unrecognized falls back to the
 * picker, the previous unconditional behavior.
 */
const RESUME_TARGET: Record<string, string> = {
  ctrader: "/broker-accounts/link/ctrader/callback",
  mt5: "/broker-accounts/link/mt5",
};

// useSearchParams() opts into client-side rendering during prerendering unless wrapped in a
// Suspense boundary -- next build fails outright without it (see ctrader/page.tsx's own
// identical Javadoc note).
function TwoFactorVerifyFormInner() {
  const router = useRouter();
  const searchParams = useSearchParams();
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
      const resume = searchParams.get("resume") ?? "";
      router.push(RESUME_TARGET[resume] ?? "/broker-accounts/link");
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
        {pending ? "Verifying…" : "Verify and continue"}
      </button>
    </form>
  );
}

export function TwoFactorVerifyForm() {
  return (
    <Suspense fallback={null}>
      <TwoFactorVerifyFormInner />
    </Suspense>
  );
}
