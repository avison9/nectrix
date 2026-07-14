import { requireSession } from "@/lib/auth";

/**
 * TICKET-110 AC1 -- the mandatory-2FA-before-trade-capable-linking gate's
 * redirect target: rendered when a linking-finalize call comes back
 * `two_factor_required`. 2FA enable/verify themselves are TICKET-005's own
 * existing /api/v1/auth/2fa/enable and /2fa/verify endpoints (phase-0,
 * already built) -- this page only needs to prompt and point at them, not
 * build a new 2FA UI from scratch (that belongs wherever this app's account
 * settings eventually live, TICKET-116's territory).
 */
export default async function TwoFactorRequiredPage() {
  await requireSession();

  return (
    <main className="mx-auto flex min-h-screen max-w-[420px] flex-col justify-center px-4 py-10">
      <h1 className="text-[20px] font-semibold tracking-tight text-[var(--text)]">
        Enable two-factor authentication
      </h1>
      <p className="mt-2 text-[13.5px] text-[var(--text-2)]">
        Linking a trade-capable broker account requires 2FA on your platform account first — this
        protects the credentials and trades a linked broker account can place.
      </p>
      <p className="mt-4 text-[13px] text-[var(--text-2)]">
        Enable it from your account settings, then return here to continue linking.
      </p>
    </main>
  );
}
