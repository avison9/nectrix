"use client";

import { Suspense, useState, useTransition } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import type { ConnectionRole } from "@nectrix/api-client";
import { linkMtAccountAction } from "./actions";

/**
 * docs/07-auth-onboarding-broker-linking.md §7.7 -- the EA-bridge credential
 * form. Submits directly (no OAuth dance); the response carries a pairing
 * token + gateway URL the user pastes into their EA's own input parameters.
 *
 * <p>useSearchParams() opts into client-side rendering during prerendering
 * unless wrapped in a Suspense boundary -- next build fails outright without
 * it (see ctrader/page.tsx's own identical Javadoc note).
 */
function ConnectMt5Form() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const openedViaIbLinkId = searchParams.get("openedViaIbLinkId") ?? undefined;

  const [platform, setPlatform] = useState<"MT5" | "MT4">("MT5");
  const [login, setLogin] = useState("");
  const [password, setPassword] = useState("");
  const [server, setServer] = useState("");
  const [isDemo, setIsDemo] = useState(true);
  const [displayLabel, setDisplayLabel] = useState("");
  const [connectionRole, setConnectionRole] = useState<ConnectionRole>("FOLLOWER_ONLY");
  const [error, setError] = useState<string | undefined>();
  const [result, setResult] = useState<{ pairingToken: string; gatewayUrl: string; id: string } | undefined>();
  const [pending, startTransition] = useTransition();

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(undefined);
    startTransition(async () => {
      const linkResult = await linkMtAccountAction(platform, {
        login,
        password,
        server,
        isDemo,
        displayLabel: displayLabel || login,
        connectionRole,
        openedViaIbLinkId,
      });
      if ("error" in linkResult) {
        if (linkResult.requiresTwoFactor) {
          router.push("/broker-accounts/2fa-required");
          return;
        }
        setError(linkResult.error);
        return;
      }
      setResult({ pairingToken: linkResult.pairingToken, gatewayUrl: linkResult.gatewayUrl, id: linkResult.id });
    });
  }

  if (result) {
    return (
      <main className="mx-auto flex min-h-screen max-w-[460px] flex-col justify-center px-4 py-10">
        <h1 className="text-[20px] font-semibold tracking-tight text-[var(--text)]">
          Pair your terminal
        </h1>
        <p className="mt-1.5 text-[13px] text-[var(--text-2)]">
          Paste these values into the bridge EA&apos;s input parameters when attaching it to a chart.
        </p>
        <div className="mt-5 flex flex-col gap-3 rounded-[12px] border border-[var(--border)] bg-[var(--surface)] p-4">
          <div>
            <span className="text-[12px] font-medium text-[var(--text-2)]">Gateway URL</span>
            <p className="mt-0.5 break-all font-mono text-[13px] text-[var(--text)]">
              {result.gatewayUrl}
            </p>
          </div>
          <div>
            <span className="text-[12px] font-medium text-[var(--text-2)]">Pairing token</span>
            <p className="mt-0.5 break-all font-mono text-[13px] text-[var(--text)]">
              {result.pairingToken}
            </p>
          </div>
        </div>
        <p className="mt-4 text-[12.5px] text-[var(--text-2)]">
          The connection stays &quot;Pending&quot; until the EA pairs with the gateway for real.
        </p>
        <button
          type="button"
          onClick={() => router.push(`/broker-accounts/symbol-mappings/${result.id}`)}
          className="mt-5 h-[42px] rounded-[11px] bg-[var(--accent)] text-[13.5px] font-semibold text-white"
        >
          Continue
        </button>
      </main>
    );
  }

  return (
    <main className="mx-auto flex min-h-screen max-w-[420px] flex-col justify-center px-4 py-10">
      <h1 className="text-[20px] font-semibold tracking-tight text-[var(--text)]">
        Connect MT5 or MT4
      </h1>

      <form onSubmit={submit} className="mt-6 flex flex-col gap-3.5">
        <div className="flex gap-2">
          {(["MT5", "MT4"] as const).map((p) => (
            <button
              key={p}
              type="button"
              onClick={() => setPlatform(p)}
              className={`h-9 flex-1 rounded-[9px] text-[13px] font-semibold ${
                platform === p
                  ? "bg-[var(--accent)] text-white"
                  : "border border-[var(--border)] text-[var(--text-2)]"
              }`}
            >
              {p}
            </button>
          ))}
        </div>

        <label className="flex flex-col gap-1.5">
          <span className="text-[12.5px] font-medium text-[var(--text-2)]">Login</span>
          <input
            required
            value={login}
            onChange={(e) => setLogin(e.target.value)}
            className="h-10 rounded-[10px] border border-[var(--border)] bg-transparent px-3 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          />
        </label>

        <label className="flex flex-col gap-1.5">
          <span className="text-[12.5px] font-medium text-[var(--text-2)]">Password</span>
          <input
            type="password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="h-10 rounded-[10px] border border-[var(--border)] bg-transparent px-3 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          />
        </label>

        <label className="flex flex-col gap-1.5">
          <span className="text-[12.5px] font-medium text-[var(--text-2)]">Server</span>
          <input
            required
            value={server}
            onChange={(e) => setServer(e.target.value)}
            placeholder="Pepperstone-Demo"
            className="h-10 rounded-[10px] border border-[var(--border)] bg-transparent px-3 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          />
        </label>

        <label className="flex flex-col gap-1.5">
          <span className="text-[12.5px] font-medium text-[var(--text-2)]">Display label</span>
          <input
            value={displayLabel}
            onChange={(e) => setDisplayLabel(e.target.value)}
            className="h-10 rounded-[10px] border border-[var(--border)] bg-transparent px-3 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          />
        </label>

        <label className="flex flex-col gap-1.5">
          <span className="text-[12.5px] font-medium text-[var(--text-2)]">Account role</span>
          <select
            value={connectionRole}
            onChange={(e) => setConnectionRole(e.target.value as ConnectionRole)}
            className="h-10 rounded-[10px] border border-[var(--border)] bg-transparent px-3 text-[13.5px] text-[var(--text)] outline-none focus:border-[var(--accent)]"
          >
            <option value="FOLLOWER_ONLY">Follower only</option>
            <option value="MASTER_ONLY">Master only</option>
            <option value="BOTH">Both</option>
          </select>
        </label>

        <label className="flex items-center gap-2">
          <input type="checkbox" checked={isDemo} onChange={(e) => setIsDemo(e.target.checked)} />
          <span className="text-[12.5px] text-[var(--text-2)]">This is a demo account</span>
        </label>

        {error && <p className="text-[12.5px] text-[var(--neg)]">{error}</p>}

        <button
          type="submit"
          disabled={pending}
          className="mt-1.5 h-[42px] rounded-[11px] bg-[var(--accent)] text-[13.5px] font-semibold text-white transition-opacity disabled:opacity-60"
        >
          {pending ? "Submitting…" : "Submit"}
        </button>
      </form>
    </main>
  );
}

export default function ConnectMt5Page() {
  return (
    <Suspense fallback={null}>
      <ConnectMt5Form />
    </Suspense>
  );
}
