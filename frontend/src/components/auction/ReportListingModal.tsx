"use client";
import { useState, useEffect } from "react";
import { useSubmitReport } from "@/hooks/auction/useSubmitReport";
import { Button } from "@/components/ui/Button";
import type { ListingReportReason } from "@/lib/admin/types";
import { REPORT_REASON_LABEL } from "@/lib/admin/reportReasonStyle";

const REASONS: ListingReportReason[] = [
  "SHILL_BIDDING",
  "FRAUDULENT_SELLER",
  "INACCURATE_DESCRIPTION",
  "WRONG_TAGS",
  "DUPLICATE_LISTING",
  "NOT_ACTUALLY_FOR_SALE",
  "TOS_VIOLATION",
  "OTHER",
];

const SUBJECT_MAX = 100;
const DETAILS_MAX = 2000;

type Props = {
  auctionId: number;
  onClose: () => void;
};

export function ReportListingModal({ auctionId, onClose }: Props) {
  const [subject, setSubject] = useState("");
  const [reason, setReason] = useState<ListingReportReason | "">("");
  const [details, setDetails] = useState("");

  const submit = useSubmitReport(auctionId);

  // ESC closes the modal.
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  const canSubmit =
    subject.trim().length > 0 &&
    reason !== "" &&
    details.trim().length > 0 &&
    !submit.isPending;

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    submit.mutate(
      { subject: subject.trim(), reason: reason as ListingReportReason, details: details.trim() },
      { onSuccess: () => { onClose(); } }
    );
  }

  return (
    <>
      <div
        className="fixed inset-0 z-50 bg-inverse-surface/20"
        onClick={onClose}
        aria-hidden="true"
      />
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="report-modal-title"
        data-testid="report-listing-modal"
        className="fixed inset-0 z-50 flex items-center justify-center p-4 pointer-events-none"
      >
        <div
          className="pointer-events-auto w-full max-w-lg bg-bg-subtle rounded-2xl shadow-md flex flex-col gap-4 p-6"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="flex items-center justify-between">
            <h2
              id="report-modal-title"
              className="text-base font-bold tracking-tight text-fg"
            >
              Report listing
            </h2>
            <button
              type="button"
              aria-label="Close"
              onClick={onClose}
              className="p-1.5 rounded-lg text-fg-muted hover:bg-bg-muted"
            >
              ✕
            </button>
          </div>

          <form onSubmit={handleSubmit} className="flex flex-col gap-4">
            {/* Subject */}
            <div className="flex flex-col gap-1">
              <label
                htmlFor="report-subject"
                className="text-xs font-medium text-fg"
              >
                Subject <span className="text-danger-flat">*</span>
              </label>
              <input
                id="report-subject"
                type="text"
                value={subject}
                maxLength={SUBJECT_MAX}
                onChange={(e) => setSubject(e.target.value)}
                placeholder="Brief summary of the issue"
                data-testid="report-subject"
                className="w-full rounded-lg bg-bg-muted px-4 py-2.5 text-fg placeholder:text-fg-muted ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand text-sm"
              />
              <div className="self-end text-[11px] font-medium text-fg-muted">
                {subject.length} / {SUBJECT_MAX}
              </div>
            </div>

            {/* Reason */}
            <div className="flex flex-col gap-1">
              <label
                htmlFor="report-reason"
                className="text-xs font-medium text-fg"
              >
                Reason <span className="text-danger-flat">*</span>
              </label>
              <select
                id="report-reason"
                value={reason}
                onChange={(e) => setReason(e.target.value as ListingReportReason | "")}
                data-testid="report-reason"
                className="w-full rounded-lg bg-bg-muted px-4 py-2.5 text-fg ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand text-sm"
              >
                <option value="" disabled>
                  Select a reason…
                </option>
                {REASONS.map((r) => (
                  <option key={r} value={r}>
                    {REPORT_REASON_LABEL[r]}
                  </option>
                ))}
              </select>
            </div>

            {/* Details */}
            <div className="flex flex-col gap-1">
              <label
                htmlFor="report-details"
                className="text-xs font-medium text-fg"
              >
                Details <span className="text-danger-flat">*</span>
              </label>
              <textarea
                id="report-details"
                rows={5}
                value={details}
                onChange={(e) => setDetails(e.target.value.slice(0, DETAILS_MAX))}
                placeholder="Describe the issue in detail…"
                data-testid="report-details"
                className="w-full resize-y rounded-lg bg-bg-muted px-4 py-3 text-fg placeholder:text-fg-muted ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand text-sm"
              />
              <div className="self-end text-[11px] font-medium text-fg-muted">
                {details.length} / {DETAILS_MAX}
              </div>
            </div>

            {/* Disclosure */}
            <div className="rounded-lg bg-bg-muted px-4 py-3 text-xs text-fg-muted">
              Your identity is shared with admin reviewers. The seller is never
              shown who reported. Frivolous reports are tracked.
            </div>

            <div className="flex justify-end gap-3">
              <Button
                type="button"
                variant="secondary"
                size="sm"
                onClick={onClose}
                disabled={submit.isPending}
              >
                Cancel
              </Button>
              <Button
                type="submit"
                variant="primary"
                size="sm"
                disabled={!canSubmit}
                loading={submit.isPending}
                data-testid="report-submit-btn"
              >
                Submit report
              </Button>
            </div>
          </form>
        </div>
      </div>
    </>
  );
}
