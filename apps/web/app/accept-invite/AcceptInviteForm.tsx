"use client";

import { useState, useTransition } from "react";
import { AcceptInviteGate } from "@/app/(app)/onboarding/AcceptInviteGate";
import { acceptInviteAction } from "./actions";

export function AcceptInviteForm({ token }: { token: string }) {
  const [gateAccepted, setGateAccepted] = useState(false);
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | undefined>();
  const [pending, startTransition] = useTransition();

  function onSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(undefined);
    startTransition(async () => {
      const formData = new FormData();
      formData.set("token", token);
      formData.set("password", password);
      const result = await acceptInviteAction({}, formData);
      if (result?.error) {
        setError(result.error);
      }
      // On success, acceptInviteAction redirects server-side — nothing else to do here.
    });
  }

  return (
    <div className="flex flex-col gap-3">
      <AcceptInviteGate onAccepted={() => setGateAccepted(true)} />

      {gateAccepted && (
        <form onSubmit={onSubmit} className="flex flex-col gap-3 rounded-[12px] border border-[var(--border)] p-4">
          <div className="text-[13px] font-medium text-[var(--text-2)]">Set your password</div>
          <input
            type="password"
            required
            minLength={12}
            autoFocus
            autoComplete="new-password"
            placeholder="At least 12 characters"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="h-11 rounded-[11px] border border-[var(--border)] bg-transparent px-3.5 text-sm text-[var(--text)] outline-none focus:border-[var(--accent)]"
          />
          {error && <p className="text-[12.5px] text-[var(--neg)]">{error}</p>}
          <button
            type="submit"
            disabled={pending}
            className="h-11 rounded-[11px] bg-[var(--accent)] text-sm font-semibold text-white transition-opacity hover:opacity-90 disabled:opacity-60"
          >
            {pending ? "Creating your account…" : "Create account & continue"}
          </button>
        </form>
      )}
    </div>
  );
}
