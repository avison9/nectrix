"use client";

import { useSearchParams } from "next/navigation";
import Link from "next/link";
import { Suspense, useState, useTransition } from "react";
import { registerAction } from "./actions";
import { PLANS } from "@/lib/plans";
import type { SubscriptionPlanCode } from "@nectrix/api-client";

// TICKET-114 — mirrors Nectrix.dc.html's `PUBLIC · SELF-SERVE REGISTRATION` section, translated
// into this app's real Tailwind + CSS-var convention (same approach app/login/page.tsx already
// established for its own form). A plan selection is required, not optional — every
// registration ends at a mandatory Stripe Checkout redirect (see ./actions.ts).
export default function RegisterPage() {
  return (
    <Suspense fallback={null}>
      <RegisterForm />
    </Suspense>
  );
}

function RegisterForm() {
  const searchParams = useSearchParams();
  const preselected = searchParams.get("plan") as SubscriptionPlanCode | null;

  const [displayName, setDisplayName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [planCode, setPlanCode] = useState<SubscriptionPlanCode>(
    PLANS.some((p) => p.code === preselected) ? (preselected as SubscriptionPlanCode) : PLANS[1].code,
  );
  const [error, setError] = useState<string | undefined>();
  const [pending, startTransition] = useTransition();

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(undefined);
    startTransition(async () => {
      const formData = new FormData();
      formData.set("displayName", displayName);
      formData.set("email", email);
      formData.set("password", password);
      formData.set("planCode", planCode);
      const result = await registerAction({}, formData);
      if (result?.error) {
        setError(result.error);
      }
    });
  }

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
        <div className="w-full max-w-[460px]">
          <div className="mb-6.5">
            <h1 className="text-2xl font-semibold tracking-tight text-[var(--text)]">
              Create your account
            </h1>
            <p className="mt-2 text-[13.5px] leading-[1.55] text-[var(--text-2)]">
              Self-serve registration — you&apos;ll be set up to trade solo on your own accounts. No
              invitation required. (Becoming a Master or Follower is always by invite, and can be
              requested later.)
            </p>
          </div>

          <form onSubmit={submit}>
            <div className="mb-4 rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-6">
              <label className="mb-1.5 block text-xs font-semibold text-[var(--text-2)]">
                Full name
              </label>
              <input
                required
                autoFocus
                autoComplete="name"
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
                className="mb-3.5 h-11 w-full rounded-[11px] border border-[var(--border)] bg-transparent px-3.5 text-sm text-[var(--text)] outline-none focus:border-[var(--accent)]"
              />

              <label className="mb-1.5 block text-xs font-semibold text-[var(--text-2)]">
                Email
              </label>
              <input
                type="email"
                required
                autoComplete="username"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className="mb-3.5 h-11 w-full rounded-[11px] border border-[var(--border)] bg-transparent px-3.5 text-sm text-[var(--text)] outline-none focus:border-[var(--accent)]"
              />

              <label className="mb-1.5 block text-xs font-semibold text-[var(--text-2)]">
                Password
              </label>
              <input
                type="password"
                required
                autoComplete="new-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="h-11 w-full rounded-[11px] border border-[var(--border)] bg-transparent px-3.5 text-sm text-[var(--text)] outline-none focus:border-[var(--accent)]"
              />
            </div>

            <div className="mb-5 rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5.5">
              <div className="mb-3 text-xs font-semibold uppercase tracking-wide text-[var(--text-3)]">
                Choose your plan
              </div>
              <div className="flex flex-col gap-1">
                {PLANS.map((p) => {
                  const selected = p.code === planCode;
                  return (
                    <div
                      key={p.code}
                      onClick={() => setPlanCode(p.code)}
                      className={`flex cursor-pointer items-center gap-3 rounded-xl p-2.5 ${
                        selected ? "bg-[var(--accent-2)]" : ""
                      }`}
                    >
                      <div
                        className={`flex h-[18px] w-[18px] flex-none items-center justify-center rounded-full border-2 ${
                          selected ? "border-[var(--accent)]" : "border-[var(--border)]"
                        }`}
                      >
                        {selected && <div className="h-[9px] w-[9px] rounded-full bg-[var(--accent)]" />}
                      </div>
                      <div className="min-w-0 flex-1">
                        <div className="text-[13.5px] font-semibold text-[var(--text)]">{p.name}</div>
                        <div className="mt-0.5 text-xs text-[var(--text-3)]">{p.desc}</div>
                      </div>
                      <div className="font-mono text-[13.5px] font-semibold text-[var(--text)]">
                        {p.price}
                        {p.per}
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>

            {error && <p className="mb-3.5 text-[12.5px] text-[var(--neg)]">{error}</p>}

            <button
              type="submit"
              disabled={pending}
              className="h-[48px] w-full rounded-xl bg-[var(--accent)] text-sm font-semibold text-white transition-all hover:-translate-y-px hover:opacity-95 disabled:opacity-60 disabled:hover:translate-y-0"
            >
              {pending ? "Creating account…" : "Create account"}
            </button>
            <p className="mt-4 text-center text-[12.5px] text-[var(--text-3)]">
              Already have a master&apos;s invite link?{" "}
              <Link href="/login" className="font-medium text-[var(--accent)]">
                Sign in instead
              </Link>
            </p>
          </form>
        </div>
      </div>
    </div>
  );
}
