"use client";

import { useState, useTransition } from "react";
import { loginAction } from "./actions";

type Step = "credentials" | "totp";

export default function LoginPage() {
  const [step, setStep] = useState<Step>("credentials");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | undefined>();
  const [pending, startTransition] = useTransition();

  function submitCredentials(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(undefined);
    startTransition(async () => {
      const formData = new FormData();
      formData.set("email", email);
      formData.set("password", password);
      // loginAction redirects on success (thrown, caught by Next's router —
      // this call never "returns" in that case), or resolves with an error.
      const result = await loginAction({}, formData);
      if (result?.error === "totp_required") {
        setStep("totp");
      } else if (result?.error) {
        setError(result.error);
      }
    });
  }

  function submitTotp(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(undefined);
    const totpCode = new FormData(event.currentTarget).get("totpCode");
    startTransition(async () => {
      const formData = new FormData();
      formData.set("email", email);
      formData.set("password", password);
      if (totpCode) {
        formData.set("totpCode", String(totpCode));
      }
      const result = await loginAction({}, formData);
      if (result?.error) {
        setError(result.error === "totp_required" ? "Enter your 2FA code." : result.error);
      }
    });
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-[var(--bg)] px-4">
      <div className="w-full max-w-[400px]">
        <div className="mb-[26px] flex flex-col items-center text-center">
          <div className="mb-4 flex h-[46px] w-[46px] items-center justify-center rounded-[13px] bg-[var(--accent)]">
            <svg
              viewBox="0 0 24 24"
              width={24}
              height={24}
              fill="none"
              stroke="#fff"
              strokeWidth={2.1}
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <path d="M5 19V7l7 5 7-5v12" />
            </svg>
          </div>
          <h1 className="text-[22px] font-semibold tracking-tight text-[var(--text)]">
            Nectrix Admin Portal
          </h1>
          <p className="mt-2 text-[13.5px] leading-[1.5] text-[var(--text-2)]">
            {step === "credentials"
              ? "Admin, Support, and Master sign-in."
              : `Enter the 2FA code for ${email}.`}
          </p>
        </div>

        <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-6">
          {step === "credentials" ? (
            <form onSubmit={submitCredentials} className="flex flex-col">
              <label className="mb-1.5 block text-xs font-semibold text-[var(--text-2)]">
                Email
              </label>
              <input
                type="email"
                required
                autoFocus
                autoComplete="username"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className="mb-3.5 h-11 w-full rounded-[11px] border border-[var(--border)] bg-transparent px-3.5 text-sm text-[var(--text)] outline-none focus:border-[var(--accent)]"
              />

              <label htmlFor="password" className="mb-1.5 block text-xs font-semibold text-[var(--text-2)]">
                Password
              </label>
              <div className="relative mb-4.5 flex items-center">
                <input
                  id="password"
                  type={showPassword ? "text" : "password"}
                  required
                  autoComplete="current-password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  className="h-11 w-full rounded-[11px] border border-[var(--border)] bg-transparent px-3.5 pr-10 text-sm text-[var(--text)] outline-none focus:border-[var(--accent)]"
                />
                <button
                  type="button"
                  onClick={() => setShowPassword((v) => !v)}
                  aria-label={showPassword ? "Hide password" : "Show password"}
                  aria-pressed={showPassword}
                  tabIndex={-1}
                  className="absolute right-1 flex h-8 w-8 items-center justify-center rounded-[8px] text-[var(--text-3)] transition-colors hover:text-[var(--text)]"
                >
                  {showPassword ? (
                    <svg
                      viewBox="0 0 24 24"
                      width={17}
                      height={17}
                      fill="none"
                      stroke="currentColor"
                      strokeWidth={1.7}
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    >
                      <path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 10 8 10 8a17.4 17.4 0 0 1-3.35 4.4M6.6 6.6C3.4 8.6 2 12 2 12s3 8 10 8a9.26 9.26 0 0 0 5.4-1.6M9.9 9.9a3 3 0 1 0 4.2 4.2" />
                      <path d="M2 2l20 20" />
                    </svg>
                  ) : (
                    <svg
                      viewBox="0 0 24 24"
                      width={17}
                      height={17}
                      fill="none"
                      stroke="currentColor"
                      strokeWidth={1.7}
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    >
                      <path d="M2 12s3-8 10-8 10 8 10 8-3 8-10 8-10-8-10-8Z" />
                      <circle cx="12" cy="12" r="3" />
                    </svg>
                  )}
                </button>
              </div>

              {error && <p className="mb-3.5 text-[12.5px] text-[var(--neg)]">{error}</p>}

              <button
                type="submit"
                disabled={pending}
                className="h-[46px] rounded-[11px] bg-[var(--accent)] text-sm font-semibold text-white transition-all hover:-translate-y-px hover:opacity-95 disabled:opacity-60 disabled:hover:translate-y-0"
              >
                {pending ? "Signing in…" : "Sign in"}
              </button>
            </form>
          ) : (
            <form onSubmit={submitTotp} className="flex flex-col">
              <label className="mb-1.5 block text-xs font-semibold text-[var(--text-2)]">
                2FA code
              </label>
              <input
                name="totpCode"
                type="text"
                required
                autoFocus
                inputMode="numeric"
                autoComplete="one-time-code"
                className="mb-4.5 h-11 w-full rounded-[11px] border border-[var(--border)] bg-transparent px-3.5 font-mono text-sm text-[var(--text)] outline-none focus:border-[var(--accent)]"
              />

              {error && <p className="mb-3.5 text-[12.5px] text-[var(--neg)]">{error}</p>}

              <button
                type="submit"
                disabled={pending}
                className="h-[46px] rounded-[11px] bg-[var(--accent)] text-sm font-semibold text-white transition-all hover:-translate-y-px hover:opacity-95 disabled:opacity-60 disabled:hover:translate-y-0"
              >
                {pending ? "Verifying…" : "Verify"}
              </button>

              <button
                type="button"
                onClick={() => {
                  setStep("credentials");
                  setError(undefined);
                }}
                className="mt-3.5 text-[12.5px] font-medium text-[var(--text-2)] underline-offset-2 hover:underline"
              >
                Back
              </button>
            </form>
          )}
        </div>
      </div>
    </main>
  );
}
