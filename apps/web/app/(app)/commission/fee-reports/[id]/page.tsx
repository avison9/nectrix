import Link from "next/link";
import { getMyBrokerFeeReport } from "@nectrix/api-client";
import { coreAppBaseUrl } from "@/lib/core-app";
import { requireSession } from "@/lib/auth";
import { FeeReportActions } from "./FeeReportActions";

/**
 * TICKET-120 — no dedicated mock section exists for this detail view (see commission/page.tsx's
 * own Javadoc); styled to match this app's established detail-page conventions (back link, stat
 * cards, line list) rather than inventing a fictional mock reference.
 */
export default async function FeeReportDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const { accessToken } = await requireSession();
  const { report, lines, documentUrl } = await getMyBrokerFeeReport(coreAppBaseUrl(), accessToken, id);

  const total = lines.reduce((sum, l) => sum + l.feeAmount, 0);

  return (
    <div className="mx-auto max-w-[800px]">
      <Link
        href="/commission"
        className="mb-4 inline-flex items-center gap-1.5 text-[13px] font-medium text-[var(--text-2)] hover:text-[var(--text)]"
      >
        ← Back to commission
      </Link>

      <div className="mb-6">
        <h1 className="text-[22px] font-semibold tracking-tight text-[var(--text)]">
          {report.brokerType} fee report
        </h1>
        <p className="mt-1 text-[13.5px] text-[var(--text-2)]">
          {new Date(report.periodStart).toLocaleDateString()} –{" "}
          {new Date(report.periodEnd).toLocaleDateString()}
        </p>
      </div>

      <div className="mb-5 grid grid-cols-2 gap-3 sm:grid-cols-3">
        <div className="rounded-[14px] border border-[var(--border)] bg-[var(--surface)] p-4">
          <div className="text-[12px] font-medium text-[var(--text-2)]">Status</div>
          <div className="mt-2 text-[16px] font-semibold text-[var(--text)]">{report.status}</div>
        </div>
        <div className="rounded-[14px] border border-[var(--border)] bg-[var(--surface)] p-4">
          <div className="text-[12px] font-medium text-[var(--text-2)]">Lines</div>
          <div className="mt-2 font-mono text-[16px] font-semibold text-[var(--text)]">
            {lines.length}
          </div>
        </div>
        <div className="rounded-[14px] border border-[var(--border)] bg-[var(--surface)] p-4">
          <div className="text-[12px] font-medium text-[var(--text-2)]">Total</div>
          <div className="mt-2 font-mono text-[16px] font-semibold text-[var(--text)]">
            {total.toFixed(2)} {lines[0]?.currency ?? ""}
          </div>
        </div>
      </div>

      <a
        href={documentUrl}
        target="_blank"
        rel="noopener noreferrer"
        className="mb-5 inline-block text-[12.5px] font-semibold text-[var(--accent)] underline-offset-2 hover:underline"
      >
        View report document
      </a>

      <div className="mb-6">
        <FeeReportActions report={report} />
      </div>

      <div className="overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)]">
        <div className="border-b border-[var(--border)] px-5 py-3.5 text-[14px] font-semibold text-[var(--text)]">
          Lines
        </div>
        {lines.map((line) => (
          <div
            key={line.id}
            className="flex items-center gap-3.5 border-t border-[var(--border)] px-5 py-3.5 first:border-t-0"
          >
            <span className="min-w-0 flex-1 truncate font-mono text-[13px] text-[var(--text)]">
              {line.followerBrokerAccountLogin}
            </span>
            <span className="whitespace-nowrap font-mono text-[13.5px] font-semibold text-[var(--text)]">
              {line.feeAmount.toFixed(2)} {line.currency}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}
