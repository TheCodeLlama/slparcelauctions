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
      className="rounded-default border border-outline-variant bg-surface-container px-4 py-3 flex flex-col gap-2"
      data-testid={`report-card-${report.id}`}
    >
      <div className="flex items-center justify-between gap-2 flex-wrap">
        <div className="flex items-center gap-2 flex-wrap">
          <ReasonBadge reason={report.reason} />
          {isDismissed && (
            <span className="text-[10px] font-medium text-on-surface-variant bg-surface-container-high px-2 py-0.5 rounded-full">
              Dismissed
            </span>
          )}
        </div>
        <span className="text-label-sm text-on-surface-variant">
          {formatDateTime(report.createdAt)}
        </span>
      </div>

      <div className="text-label-md font-medium text-on-surface">{report.subject}</div>

      {report.details && (
        <div className="text-body-sm text-on-surface-variant whitespace-pre-wrap">
          {report.details}
        </div>
      )}

      <div className="flex items-center justify-between gap-2 mt-1">
        <div className="text-body-sm text-on-surface-variant">
          By{" "}
          {report.reporterDisplayName ? (
            <Link
              href={`/admin/users/${report.reporterUserId}`}
              className="text-primary underline underline-offset-2"
            >
              {report.reporterDisplayName}
            </Link>
          ) : (
            <span>Unknown</span>
          )}
          {report.reporterDismissedReportsCount > 0 && (
            <span className="ml-1 text-error text-[11px]">
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
