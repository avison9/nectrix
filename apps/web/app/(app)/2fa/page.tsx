import Link from "next/link";
import { beginTwoFactorEnrollment } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";
import { TwoFactorVerifyForm } from "./TwoFactorVerifyForm";

/**
 * TICKET-116 — Profile's own "Two-factor authentication" row was inert since TICKET-115 (no
 * settings sub-page existed yet, see that page's own comment) — this is that page, wired now.
 * Reuses the exact TICKET-005 enrollment API (`beginTwoFactorEnrollment`/`verifyTwoFactor`) that
 * previously only had a UI inside the broker-linking mandatory-2FA gate
 * (broker-accounts/2fa-required).
 */
export default async function TwoFactorSettingsPage() {
  const { session, accessToken } = await requireSession();

  if (session.twoFactorEnabled) {
    return (
      <div className="mx-auto max-w-[420px]">
        <h1 className="text-[20px] font-semibold tracking-tight text-[var(--text)]">
          Two-factor authentication
        </h1>
        <p className="mt-2 text-[13.5px] text-[var(--text-2)]">
          Two-factor authentication is enabled on your account.
        </p>
        <Link
          href="/profile"
          className="mt-6 inline-block text-[12.5px] font-medium text-[var(--text-2)] underline-offset-2 hover:underline"
        >
          Back to profile
        </Link>
      </div>
    );
  }

  const { secret, qrCodeUri } = await beginTwoFactorEnrollment(coreAppBaseUrl(), accessToken);

  return (
    <div className="mx-auto max-w-[420px]">
      <h1 className="text-[20px] font-semibold tracking-tight text-[var(--text)]">
        Enable two-factor authentication
      </h1>
      <p className="mt-2 text-[13.5px] text-[var(--text-2)]">
        Adds a second step when signing in — protects your account and any broker accounts linked
        to it.
      </p>

      <div className="mt-5 flex flex-col items-center gap-3 rounded-[12px] border border-[var(--border)] bg-[var(--surface)] p-4">
        {/* eslint-disable-next-line @next/next/no-img-element -- a data: URI, not a static asset next/image can optimize */}
        <img src={qrCodeUri} alt="Scan with your authenticator app" width={180} height={180} />
        <p className="text-center text-[12px] text-[var(--text-2)]">
          Scan with Google Authenticator, Authy, 1Password, or any TOTP app. Can&apos;t scan? Enter
          this code manually:
        </p>
        <code className="break-all rounded-[8px] bg-[var(--accent-2)] px-2.5 py-1.5 text-[12.5px] text-[var(--text)]">
          {secret}
        </code>
      </div>

      <TwoFactorVerifyForm />
    </div>
  );
}
