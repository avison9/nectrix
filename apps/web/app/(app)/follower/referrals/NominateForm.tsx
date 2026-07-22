"use client";

import { useRef, useState, useTransition } from "react";
import { nominateProspectAction } from "./actions";

export function NominateForm() {
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
      const result = await nominateProspectAction(null, formData);
      if ("error" in result) {
        setError(result.error);
        return;
      }
      setSent(true);
      formRef.current?.reset();
    });
  }

  return (
    <form ref={formRef} onSubmit={onSubmit} className="flex flex-wrap gap-2.5">
      <input
        name="prospectEmail"
        type="email"
        required
        placeholder="prospect@email.com"
        className="h-11 min-w-[200px] flex-1 rounded-[11px] border border-[var(--border)] bg-transparent px-3.5 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
      />
      <button
        type="submit"
        disabled={pending}
        className="h-11 rounded-[11px] bg-[var(--accent)] px-5 text-[13.5px] font-semibold text-white transition-opacity hover:opacity-90 disabled:opacity-60"
      >
        {pending ? "Sending…" : "Send to my master"}
      </button>
      {error && <p className="mt-2 w-full text-[12.5px] text-[var(--neg)]">{error}</p>}
      {sent && <p className="mt-2 w-full text-[12.5px] text-[var(--pos)]">Sent to your master.</p>}
    </form>
  );
}
