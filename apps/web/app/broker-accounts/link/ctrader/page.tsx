"use client";

import { useSearchParams } from "next/navigation";
import { useState, useTransition } from "react";
import { getAuthorizeUrlAction } from "./actions";

/**
 * docs/07-auth-onboarding-broker-linking.md §7.6 -- real, live-verified
 * integration requirement documented on BrokerAccountOAuthController: cTrader's
 * own redirect does NOT echo the `state` query param back, only `code`. This
 * page is the piece core-app's own Javadoc says must exist: parse `state` out
 * of the authorize URL BEFORE navigating, persist it in localStorage (it must
 * survive a full top-level navigation), and the callback page re-attaches it.
 */
export default function ConnectCtraderPage() {
  const [error, setError] = useState<string | undefined>();
  const [pending, startTransition] = useTransition();
  const searchParams = useSearchParams();
  const openedViaIbLinkId = searchParams.get("openedViaIbLinkId");

  function connect() {
    setError(undefined);
    startTransition(async () => {
      const result = await getAuthorizeUrlAction();
      if ("error" in result) {
        setError(result.error);
        return;
      }
      const url = new URL(result.authorizeUrl);
      const state = url.searchParams.get("state");
      if (state) {
        window.localStorage.setItem("ctrader_oauth_state", state);
      }
      if (openedViaIbLinkId) {
        window.localStorage.setItem("ctrader_opened_via_ib_link_id", openedViaIbLinkId);
      }
      window.location.href = result.authorizeUrl;
    });
  }

  return (
    <main className="mx-auto flex min-h-screen max-w-[420px] flex-col justify-center px-4 py-10">
      <h1 className="text-[20px] font-semibold tracking-tight text-[var(--text)]">
        Connect cTrader
      </h1>
      <p className="mt-1.5 text-[13px] text-[var(--text-2)]">
        You&apos;ll be redirected to cTrader to sign in and approve trading access.
      </p>

      {error && <p className="mt-4 text-[12.5px] text-[var(--neg)]">{error}</p>}

      <button
        type="button"
        onClick={connect}
        disabled={pending}
        className="mt-6 h-[42px] rounded-[11px] bg-[var(--accent)] text-[13.5px] font-semibold text-white transition-opacity disabled:opacity-60"
      >
        {pending ? "Connecting…" : "Connect cTrader account"}
      </button>
    </main>
  );
}
