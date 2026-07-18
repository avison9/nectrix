"use client";

import Link from "next/link";
import { useState } from "react";

/**
 * TICKET-116 — mirrors Nectrix.dc.html's `PUBLIC · REQUEST INVITE` (`isRequestInvite`, `:228-264`).
 * Sits between TICKET-112 (discovery — browsing masters is real) and TICKET-118 (invitation
 * acceptance, not built) — there is no backend endpoint to actually route a request to a master
 * yet. Same "rendered but inert" precedent app/masters/page.tsx already established for its own
 * not-yet-wired "Request invite" button: the form renders per the mock's own layout, submit is
 * disabled with a clear tooltip, no fetch call to an endpoint that doesn't exist.
 */
export default function RequestInvitePage() {
  const [masterHandle, setMasterHandle] = useState("");
  const [email, setEmail] = useState("");

  return (
    <div className="min-h-screen bg-[var(--bg)]">
      <header className="flex h-[68px] items-center gap-3 border-b border-[var(--border)] bg-[var(--surface)] px-7">
        <Link
          href="/"
          className="flex items-center gap-2 text-[17px] font-semibold tracking-tight text-[var(--text)]"
        >
          <span aria-hidden>←</span>Nectrix
        </Link>
      </header>

      <div className="flex justify-center px-6 pb-20 pt-14">
        <div className="w-full max-w-[440px]">
          <div className="mb-5.5">
            <h1 className="text-2xl font-semibold tracking-tight text-[var(--text)]">
              Request an invite
            </h1>
            <p className="mt-2 text-[13.5px] leading-[1.55] text-[var(--text-2)]">
              Invites come only from a master, as a unique link. If you already trade with one, ask
              them directly — or send a request here and they&apos;ll follow up.
            </p>
          </div>

          <div className="mb-5 rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-6">
            <label className="mb-1.5 block text-xs font-semibold text-[var(--text-2)]">
              Master&apos;s email or handle
            </label>
            <input
              value={masterHandle}
              onChange={(e) => setMasterHandle(e.target.value)}
              placeholder="marcus-mx"
              className="mb-3.5 h-11 w-full rounded-[11px] border border-[var(--border)] bg-transparent px-3.5 text-sm text-[var(--text)] outline-none focus:border-[var(--accent)]"
            />
            <label className="mb-1.5 block text-xs font-semibold text-[var(--text-2)]">
              Your email
            </label>
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@email.com"
              className="h-11 w-full rounded-[11px] border border-[var(--border)] bg-transparent px-3.5 text-sm text-[var(--text)] outline-none focus:border-[var(--accent)]"
            />
          </div>

          <button
            type="button"
            disabled
            title="Sending an invite request isn't available yet — invitation acceptance hasn't shipped"
            className="h-12 w-full cursor-not-allowed rounded-xl bg-[var(--accent)] text-[14.5px] font-semibold text-white opacity-60"
          >
            Send request
          </button>

          <p className="mt-4 text-center text-[12.5px] text-[var(--text-3)]">
            Prefer to trade solo instead?{" "}
            <Link href="/register" className="text-[var(--accent)] underline-offset-2 hover:underline">
              Register as an individual
            </Link>
          </p>
        </div>
      </div>
    </div>
  );
}
