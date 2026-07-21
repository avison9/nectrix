import { listMyBrokerIbLinks } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";
import { AddIbLinkForm } from "./AddIbLinkForm";
import { DeactivateIbLinkButton } from "./DeactivateIbLinkButton";

const BROKER_TYPE_LABEL: Record<string, string> = {
  CTRADER: "cTrader",
  MT5: "MT5",
  MT4: "MT4",
};

/**
 * TICKET-119 — mirrors Nectrix.dc.html's `MASTER · IB PARTNERS` (`vMasterIb`, `:678-717`), now
 * wired to the real Broker IB Link CRUD. One deliberate, disclosed deviation from the mock: the
 * mock's own "Followers"/"Rebate" stats per card have no real data source (no such tracking exists
 * anywhere in the backend) — honestly shown as an active/inactive badge instead of fabricated
 * numbers, same "don't fake data" precedent every other real-data page this session established.
 * The mock's fixed 3-broker-slot layout is also replaced with a real, open-ended list (the actual
 * ticket scope is "create any number of named links," not "edit 3 preset broker slots") plus an
 * add-new form, while keeping the mock's card visual language.
 */
export default async function IbPartnersPage() {
  const { session, accessToken } = await requireSession();

  if (!session.roles.includes("MASTER")) {
    return (
      <div className="mx-auto max-w-[480px] py-16 text-center">
        <h1 className="text-[20px] font-semibold text-[var(--text)]">IB partner links</h1>
        <p className="mt-2 text-[13.5px] text-[var(--text-2)]">
          This page is only available to Master accounts.
        </p>
      </div>
    );
  }

  const links = await listMyBrokerIbLinks(coreAppBaseUrl(), accessToken);

  return (
    <div className="mx-auto max-w-[1080px]">
      <div className="mb-6">
        <h1 className="text-[25px] font-semibold tracking-tight text-[var(--text)]">
          IB partner links
        </h1>
        <p className="mt-1.5 text-[14px] text-[var(--text-2)]">
          Set your Introducing Broker link for each broker. New followers open accounts under these
          links so you earn broker rebates.
        </p>
      </div>

      <div className="mb-5">
        <AddIbLinkForm />
      </div>

      {links.length === 0 ? (
        <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] px-5 py-6 text-[13px] text-[var(--text-2)]">
          No IB links yet — add one above.
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {links.map((link) => (
            <div
              key={link.id}
              className={`flex flex-col gap-4 rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5 ${link.isActive ? "" : "opacity-60"}`}
            >
              <div className="flex items-center gap-3">
                <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-[11px] border border-[var(--border)] bg-[var(--surface-2)] text-[15px] font-bold text-[var(--text)]">
                  {link.brokerDisplayName.slice(0, 2).toUpperCase()}
                </div>
                <div className="min-w-0 flex-1">
                  <div className="truncate text-[15px] font-semibold text-[var(--text)]">
                    {link.brokerDisplayName}
                  </div>
                  <div className="text-[12px] text-[var(--text-3)]">
                    {BROKER_TYPE_LABEL[link.brokerType] ?? link.brokerType}
                  </div>
                </div>
                <span
                  className={`shrink-0 rounded-full px-2.5 py-1 text-[11px] font-semibold whitespace-nowrap ${
                    link.isActive
                      ? "bg-[var(--pos)]/15 text-[var(--pos)]"
                      : "bg-[var(--surface-2)] text-[var(--text-3)]"
                  }`}
                >
                  {link.isActive ? "Active" : "Inactive"}
                </span>
              </div>
              <div>
                <div className="mb-1.5 text-[11.5px] font-medium text-[var(--text-2)]">
                  IB / affiliate link
                </div>
                <div className="truncate rounded-[9px] border border-[var(--border)] bg-[var(--surface-2)] px-3 py-2.5 font-mono text-[12px] text-[var(--text)]">
                  {link.ibReferralUrlOrCode}
                </div>
              </div>
              {link.isActive && (
                <div className="flex justify-end border-t border-[var(--border)] pt-3.5">
                  <DeactivateIbLinkButton id={link.id} />
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
