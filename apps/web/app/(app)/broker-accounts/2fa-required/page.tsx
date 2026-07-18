import { beginTwoFactorEnrollment } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";
import { TwoFactorVerifyForm } from "./TwoFactorVerifyForm";

/**
 * TICKET-110 AC1 -- the mandatory-2FA-before-trade-capable-linking gate's
 * redirect target, rendered when a linking-finalize call comes back
 * `two_factor_required`. A real enrollment flow, not just a prompt: fetches a
 * fresh secret + QR code from TICKET-005's existing /api/v1/auth/2fa/enable
 * every time this page loads (safe to call repeatedly — TwoFactorService's
 * own design overwrites the prior pending secret cleanly), so scanning then
 * reloading this page means re-scanning, never a broken half-enrolled state.
 */
export default async function TwoFactorRequiredPage() {
  const { accessToken } = await requireSession();
  const { secret, qrCodeUri } = await beginTwoFactorEnrollment(coreAppBaseUrl(), accessToken);

  return (
    <div className="mx-auto flex min-h-[60vh] max-w-[420px] flex-col justify-center">
      <h1 className="text-[20px] font-semibold tracking-tight text-[var(--text)]">
        Enable two-factor authentication
      </h1>
      <p className="mt-2 text-[13.5px] text-[var(--text-2)]">
        Linking a trade-capable broker account requires 2FA on your platform account first — this
        protects the credentials and trades a linked broker account can place.
      </p>

      <div className="mt-5 flex flex-col items-center gap-3 rounded-[12px] border border-[var(--border)] bg-[var(--surface)] p-4">
        {/* eslint-disable-next-line @next/next/no-img-element -- a data: URI, not a static asset next/image can optimize */}
        <img src={qrCodeUri} alt="Scan with your authenticator app" width={180} height={180} />
        <p className="text-center text-[12px] text-[var(--text-2)]">
          Scan with Google Authenticator, Authy, 1Password, or any TOTP app. Can&apos;t scan?
          Enter this code manually:
        </p>
        <code className="break-all rounded-[8px] bg-[var(--accent-2)] px-2.5 py-1.5 text-[12.5px] text-[var(--text)]">
          {secret}
        </code>
      </div>

      <TwoFactorVerifyForm />
    </div>
  );
}
