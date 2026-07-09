"use client";

import { useActionState } from "react";
import { loginAction, type LoginActionState } from "./actions";

const initialState: LoginActionState = {};

export default function LoginPage() {
  const [state, formAction, pending] = useActionState(loginAction, initialState);

  return (
    <main className="flex min-h-screen items-center justify-center bg-[var(--bg)] px-4">
      <div className="w-full max-w-[360px] rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-7">
        <h1 className="text-[20px] font-semibold tracking-tight text-[var(--text)]">
          Nectrix Admin Portal
        </h1>
        <p className="mt-1.5 text-[13px] text-[var(--text-2)]">
          Admin, Support, and Master sign-in.
        </p>

        <form action={formAction} className="mt-6 flex flex-col gap-3.5">
          <label className="flex flex-col gap-1.5">
            <span className="text-[12.5px] font-medium text-[var(--text-2)]">Email</span>
            <input
              name="email"
              type="email"
              required
              autoComplete="username"
              className="h-10 rounded-[10px] border border-[var(--border)] bg-transparent px-3 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
            />
          </label>

          <label className="flex flex-col gap-1.5">
            <span className="text-[12.5px] font-medium text-[var(--text-2)]">Password</span>
            <input
              name="password"
              type="password"
              required
              autoComplete="current-password"
              className="h-10 rounded-[10px] border border-[var(--border)] bg-transparent px-3 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
            />
          </label>

          <label className="flex flex-col gap-1.5">
            <span className="text-[12.5px] font-medium text-[var(--text-2)]">
              2FA code <span className="text-[var(--text-3)]">(if enabled)</span>
            </span>
            <input
              name="totpCode"
              type="text"
              inputMode="numeric"
              autoComplete="one-time-code"
              className="h-10 rounded-[10px] border border-[var(--border)] bg-transparent px-3 font-mono text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
            />
          </label>

          {state.error && (
            <p className="text-[12.5px] text-[var(--neg)]">
              {state.error === "totp_required"
                ? "This account has 2FA enabled — enter your code above."
                : state.error}
            </p>
          )}

          <button
            type="submit"
            disabled={pending}
            className="mt-1.5 h-[42px] rounded-[11px] bg-[var(--accent)] text-[13.5px] font-semibold text-white transition-opacity disabled:opacity-60"
          >
            {pending ? "Signing in…" : "Sign in"}
          </button>
        </form>
      </div>
    </main>
  );
}
