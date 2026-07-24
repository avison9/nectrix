import Link from "next/link";
import { getCopyRelationship } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";
import { fetchOrNotFound } from "@/lib/fetchOrNotFound";
import { CopyRelationshipStatusBadge } from "@/components/CopyRelationshipStatusBadge";
import { CopyRelationshipActions } from "./CopyRelationshipActions";
import { CopySettingsForm } from "./CopySettingsForm";

export default async function CopyRelationshipDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { accessToken } = await requireSession();
  const { id } = await params;
  const relationship = await fetchOrNotFound(getCopyRelationship(coreAppBaseUrl(), accessToken, id));

  return (
    <div className="mx-auto max-w-[560px]">
      <Link
        href="/copy-relationships"
        className="mb-4 inline-flex h-[34px] items-center gap-1.5 rounded-[9px] border border-[var(--border)] px-3 text-[13px] font-semibold text-[var(--text-2)] transition-colors hover:bg-[var(--surface-2)]"
      >
        <svg viewBox="0 0 24 24" width={15} height={15} fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
          <path d="M15 18l-6-6 6-6" />
        </svg>
        Back to my masters
      </Link>

      <div className="flex items-center justify-between">
        <h1 className="text-[20px] font-semibold tracking-tight text-[var(--text)]">
          Copy relationship
        </h1>
        <CopyRelationshipStatusBadge status={relationship.status} />
      </div>
      <p className="mt-1.5 font-mono text-[12.5px] text-[var(--text-2)]">{relationship.id}</p>

      <CopyRelationshipActions relationship={relationship} />

      <div className="mt-8">
        <h2 className="text-[15px] font-semibold text-[var(--text)]">Copy settings</h2>
        <div className="mt-3 rounded-[12px] border border-[var(--border)] bg-[var(--surface)] px-4">
          <Row label="Copy mode" value={relationship.moneyManagementProfile.method} />
          {relationship.moneyManagementProfile.multiplier !== null && (
            <Row
              label="Lot multiplier"
              value={`${relationship.moneyManagementProfile.multiplier}×`}
              mono
            />
          )}
          {relationship.moneyManagementProfile.fixedLotSize !== null && (
            <Row
              label="Fixed lot size"
              value={String(relationship.moneyManagementProfile.fixedLotSize)}
              mono
            />
          )}
          {relationship.riskProfile.maxLotPerTrade !== null && (
            <Row label="Max lot per trade" value={String(relationship.riskProfile.maxLotPerTrade)} mono />
          )}
          {relationship.riskProfile.drawdownPausePct !== null && (
            <Row
              label="Drawdown pause"
              value={`${relationship.riskProfile.drawdownPausePct}%`}
              mono
            />
          )}
          {relationship.riskProfile.drawdownCloseAllPct !== null && (
            <Row
              label="Drawdown stop (force-close)"
              value={`${relationship.riskProfile.drawdownCloseAllPct}%`}
              mono
              last
            />
          )}
        </div>
        <CopySettingsForm relationship={relationship} />
      </div>
    </div>
  );
}

function Row({
  label,
  value,
  mono,
  last,
}: {
  label: string;
  value: string;
  mono?: boolean;
  last?: boolean;
}) {
  return (
    <div
      className={`flex items-center justify-between py-3 ${last ? "" : "border-b border-[var(--border)]"}`}
    >
      <span className="text-[13px] font-medium text-[var(--text)]">{label}</span>
      <span
        className={`text-[13px] text-[var(--text-2)] ${mono ? "font-mono text-[var(--accent)]" : ""}`}
      >
        {value}
      </span>
    </div>
  );
}
