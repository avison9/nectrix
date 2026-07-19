"use client";

import { useRef, useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import { createInvitationAction } from "./actions";

export function InviteForm() {
  const router = useRouter();
  const formRef = useRef<HTMLFormElement>(null);
  const [error, setError] = useState<string | undefined>();
  const [sent, setSent] = useState(false);
  const [pending, startTransition] = useTransition();

  function onSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(undefined);
    setSent(false);
    const formData = new FormData(e.currentTarget);
    startTransition(async () => {
      const result = await createInvitationAction(null, formData);
      if ("error" in result) {
        setError(result.error);
        return;
      }
      setSent(true);
      formRef.current?.reset();
      router.refresh();
    });
  }

  return (
    <div className="mt-3.5 border-t border-[var(--border)] pt-3.5">
      <div className="mb-2.5 text-[13px] font-medium text-[var(--text-2)]">Or invite by email</div>
      <form ref={formRef} onSubmit={onSubmit} className="flex flex-wrap gap-2.5">
        <input
          name="invitedEmail"
          type="email"
          required
          placeholder="follower@email.com"
          className="h-[42px] min-w-[200px] flex-1 rounded-[11px] border border-[var(--border)] bg-transparent px-3.5 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
        />
        <button
          type="submit"
          disabled={pending}
          className="h-[42px] rounded-[11px] border border-[var(--border)] px-4.5 text-[13.5px] font-semibold text-[var(--text)] transition-colors hover:bg-[var(--surface-2)] disabled:opacity-60"
        >
          {pending ? "Sending…" : "Send invite"}
        </button>
      </form>
      {error && <p className="mt-2 text-[12.5px] text-[var(--neg)]">{error}</p>}
      {sent && <p className="mt-2 text-[12.5px] text-[var(--pos)]">Invite sent.</p>}
    </div>
  );
}
