import Link from "next/link";
import { requireSession } from "@/lib/auth";

/**
 * docs/07-auth-onboarding-broker-linking.md §7.5's flowchart node B: "Broker
 * type?" -- cTrader (OAuth), MT5/MT4 (credential form), or "I don't have an
 * account yet" (the IB-link sub-flow, §7.4).
 */
export default async function LinkBrokerAccountPage() {
  await requireSession();

  return (
    <div className="mx-auto flex min-h-[60vh] max-w-[480px] flex-col justify-center">
      <h1 className="text-[20px] font-semibold tracking-tight text-[var(--text)]">
        Link a broker account
      </h1>
      <p className="mt-1.5 text-[13px] text-[var(--text-2)]">Choose how you&apos;d like to connect.</p>

      <div className="mt-6 flex flex-col gap-3">
        <Link
          href="/broker-accounts/link/ctrader"
          className="rounded-[12px] border border-[var(--border)] bg-[var(--surface)] p-4 text-[14px] font-medium text-[var(--text)] transition-colors hover:border-[var(--accent)]"
        >
          Connect cTrader
          <span className="mt-1 block text-[12.5px] font-normal text-[var(--text-2)]">
            Authorize via cTrader&apos;s own sign-in page.
          </span>
        </Link>

        <Link
          href="/broker-accounts/link/mt5"
          className="rounded-[12px] border border-[var(--border)] bg-[var(--surface)] p-4 text-[14px] font-medium text-[var(--text)] transition-colors hover:border-[var(--accent)]"
        >
          Connect MT5 or MT4
          <span className="mt-1 block text-[12.5px] font-normal text-[var(--text-2)]">
            Submit your terminal login and pair the bridge EA.
          </span>
        </Link>

        <Link
          href="/broker-accounts/link/ib-link"
          className="rounded-[12px] border border-[var(--border)] bg-[var(--surface)] p-4 text-[14px] font-medium text-[var(--text)] transition-colors hover:border-[var(--accent)]"
        >
          I don&apos;t have an account yet
          <span className="mt-1 block text-[12.5px] font-normal text-[var(--text-2)]">
            Open one via your Master&apos;s introducing-broker link.
          </span>
        </Link>
      </div>
    </div>
  );
}
