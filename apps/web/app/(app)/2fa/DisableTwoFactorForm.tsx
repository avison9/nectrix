"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { disableTwoFactorAction } from "./actions";

/**
 * TICKET-117 bugfix — replaces the permanently `disabled` "Disable two-factor authentication"
 * button. Requires re-entering a current TOTP code (same proof-of-possession the login challenge
 * itself requires — see TwoFactorService#disable's own Javadoc) rather than a bare click.
 */
export function DisableTwoFactorForm() {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | undefined>();
  const [pending, startTransition] = useTransition();

  if (!open) {
    return (
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="h-[42px] rounded-[11px] border border-[var(--border)] px-4.5 text-[13.5px] font-semibold text-[var(--neg)] transition-colors hover:bg-[var(--neg)]/8"
      >
        Disable two-factor authentication
      </button>
    );
  }

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(undefined);
    startTransition(async () => {
      const result = await disableTwoFactorAction(code);
      if (result.error) {
        setError(result.error);
        return;
      }
      router.push("/profile");
      router.refresh();
    });
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-3">
      <p className="text-[12.5px] text-[var(--text-2)]">
        Enter a current code from your authenticator app to confirm.
      </p>
      <div className="flex flex-wrap items-center gap-3">
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
        <button
          type="submit"
          disabled={pending || code.length !== 6}
          className="h-[42px] rounded-[11px] bg-[var(--neg)] px-4.5 text-[13.5px] font-semibold text-white transition-opacity disabled:opacity-60"
        >
          {pending ? "Disabling…" : "Confirm disable"}
        </button>
        <button
          type="button"
          onClick={() => {
            setOpen(false);
            setCode("");
            setError(undefined);
          }}
          className="h-[42px] rounded-[11px] border border-[var(--border)] px-4.5 text-[13.5px] font-semibold text-[var(--text-2)] transition-colors hover:bg-[var(--surface-2)]"
        >
          Cancel
        </button>
      </div>
      {error && <p className="text-[12.5px] text-[var(--neg)]">{error}</p>}
    </form>
  );
}
