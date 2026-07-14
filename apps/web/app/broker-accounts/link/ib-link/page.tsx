import Link from "next/link";
import { listMasterIbLinks } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";

/**
 * docs/07-auth-onboarding-broker-linking.md §7.4 -- "open a new account via
 * IB link" sub-flow. TICKET-118 (invitation acceptance) isn't built yet, so
 * there's no real handoff carrying the inviting Master's profile id into this
 * page -- masterProfileId is accepted as a query param for now, honest
 * scaffolding until that flow exists to supply it for real. Renders
 * gracefully with zero results (BrokerIbLinkController's own design already
 * returns [] rather than an error for an unknown/absent id).
 */
export default async function IbLinkSubFlowPage({
  searchParams,
}: {
  searchParams: Promise<{ masterProfileId?: string }>;
}) {
  const { accessToken } = await requireSession();
  const { masterProfileId } = await searchParams;

  const ibLinks = masterProfileId
    ? await listMasterIbLinks(coreAppBaseUrl(), accessToken, masterProfileId)
    : [];

  return (
    <main className="mx-auto flex min-h-screen max-w-[480px] flex-col justify-center px-4 py-10">
      <h1 className="text-[20px] font-semibold tracking-tight text-[var(--text)]">
        Open a new broker account
      </h1>

      {!masterProfileId ? (
        <p className="mt-4 text-[13px] text-[var(--text-2)]">
          No inviting Master context is available yet — this sub-flow is normally reached mid
          invitation-acceptance. Link an existing account instead.
        </p>
      ) : ibLinks.length === 0 ? (
        <p className="mt-4 text-[13px] text-[var(--text-2)]">
          Your Master doesn&apos;t have any active introducing-broker links yet. Link an existing
          account instead, or check back later.
        </p>
      ) : (
        <div className="mt-6 flex flex-col gap-3">
          <p className="text-[13px] text-[var(--text-2)]">
            Opening an account through one of these links lets your Master&apos;s broker
            partnership recognize your account.
          </p>
          {ibLinks.map((link) => (
            <div
              key={link.id}
              className="rounded-[12px] border border-[var(--border)] bg-[var(--surface)] p-4"
            >
              <p className="text-[14px] font-medium text-[var(--text)]">{link.brokerDisplayName}</p>
              <p className="mt-0.5 text-[12.5px] text-[var(--text-2)]">{link.brokerType}</p>
              <a
                href={link.ibReferralUrlOrCode}
                target="_blank"
                rel="noreferrer"
                className="mt-3 inline-block rounded-[9px] bg-[var(--accent)] px-3 py-1.5 text-[12.5px] font-semibold text-white"
              >
                Open account at {link.brokerDisplayName}
              </a>
              <p className="mt-2 text-[12px] text-[var(--text-3)]">
                Once you have your login, come back and{" "}
                <Link
                  href={`/broker-accounts/link/${link.brokerType === "CTRADER" ? "ctrader" : "mt5"}?openedViaIbLinkId=${link.id}`}
                  className="underline underline-offset-2"
                >
                  link the new account
                </Link>
                .
              </p>
            </div>
          ))}
        </div>
      )}

      <Link
        href="/broker-accounts/link"
        className="mt-6 text-[12.5px] font-medium text-[var(--text-2)] underline-offset-2 hover:underline"
      >
        Back
      </Link>
    </main>
  );
}
