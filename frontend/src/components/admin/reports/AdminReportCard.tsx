"use client";
import Link from "next/link";
import { Button } from "@/components/ui/Button";
import { ReasonBadge } from "./ReasonBadge";
import { useDismissReport } from "@/hooks/admin/useDismissReport";
import type { AdminReportDetail } from "@/lib/admin/types";

function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString("en-US", {
    month: "short",
    day: "numeric",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

type Props = {
  report: AdminReportDetail;
  onDismissed?: () => void;
};

export function AdminReportCard({ report, onDismissed }: Props) {
  const dismiss = useDismissReport();

  const handleDismiss = () => {
    dismiss.mutate(
      { reportId: report.id, notes: "" },
      { onSuccess: () => { onDismissed?.(); } }
    );
  };

  const isDismissed = report.status === "DISMISSED";

  return (
    <div
      className="rounded-lg border border-border-subtle bg-bg-muted px-4 py-3 flex flex-col gap-2"
      data-testid={`report-card-${report.id}`}
    >
      <div className="flex items-center justify-between gap-2 flex-wrap">
        <div className="flex items-center gap-2 flex-wrap">
          <ReasonBadge reason={report.reason} />
          {isDismissed && (
            <span className="text-[10px] font-medium text-fg-muted bg-bg-hover px-2 py-0.5 rounded-full">
              Dismissed
            </span>
          )}
        </div>
        <span className="text-[11px] font-medium text-fg-muted">
          {formatDateTime(report.createdAt)}
        </span>
      </div>

      <div className="text-xs font-medium text-fg">{report.subject}</div>

      {report.details && (
        <div className="text-sm text-fg-muted whitespace-pre-wrap">
          {report.details}
        </div>
      )}

      <div className="flex items-center justify-between gap-2 mt-1">
        <div className="text-sm text-fg-muted">
          By{" "}
          {report.reporterDisplayName ? (
            <Link
              href={`/admin/users/${report.reporterUserId}`}
              className="text-brand underline underline-offset-2"
            >
              {report.reporterDisplayName}
            </Link>
          ) : (
            <span>Unknown</span>
          )}
          {report.reporterDismissedReportsCount > 0 && (
            <span className="ml-1 text-danger text-[11px]">
              ({report.reporterDismissedReportsCount} prior dismissed)
            </span>
          )}
        </div>

        {!isDismissed && (
          <Button
            variant="secondary"
            size="sm"
            onClick={handleDismiss}
            loading={dismiss.isPending}
            data-testid={`dismiss-report-btn-${report.id}`}
          >
            Dismiss
          </Button>
        )}
      </div>
    </div>
  );
}
