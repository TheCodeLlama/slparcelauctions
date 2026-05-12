"use client";

import { useState } from "react";
import { Modal } from "@/components/ui/Modal";
import { Button } from "@/components/ui/Button";
import { useToast } from "@/components/ui/Toast/useToast";
import { isApiError } from "@/lib/api";
import { useSubmitGroupReport } from "@/hooks/realty/useSubmitGroupReport";
import type { RealtyGroupReportReason } from "@/types/realty";

/**
 * Friendly labels for each {@link RealtyGroupReportReason}. The enum names
 * are wire-shapes; this map is the single place the public-facing copy
 * lives. Admin-side surfaces re-use this map via {@link reportReasonLabel}.
 */
const REASON_LABELS: Record<RealtyGroupReportReason, string> = {
  FRAUDULENT_LISTINGS: "Fraudulent listings",
  MISLEADING_ATTRIBUTION: "Misleading attribution",
  HARASSMENT: "Harassment",
  IMPERSONATION: "Impersonation",
  SPAM: "Spam",
  OTHER: "Other",
};

const REASONS: RealtyGroupReportReason[] = [
  "FRAUDULENT_LISTINGS",
  "MISLEADING_ATTRIBUTION",
  "HARASSMENT",
  "IMPERSONATION",
  "SPAM",
  "OTHER",
];

const DETAILS_MIN = 10;
const DETAILS_MAX = 2000;

export interface ReportGroupModalProps {
  /** Public id of the group being reported. Used by the mutation hook. */
  groupPublicId: string;
  /** Modal open flag — parent owns the open state. */
  open: boolean;
  /** Called when the modal should dismiss (success or cancel). */
  onClose: () => void;
}

/**
 * Public-facing "Report group" modal. Mirrors the auction
 * {@code ReportListingModal} but uses our shared {@link Modal} primitive
 * and the realty groups-specific error shapes.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>Reason dropdown over the {@code RealtyGroupReportReason} enum.</li>
 *   <li>Details textarea — 10–2000 chars, with a live counter and a
 *       client-side validation message that blocks submit.</li>
 *   <li>Submit calls {@link useSubmitGroupReport}. On success: close +
 *       success toast.</li>
 *   <li>Error 409/429 codes render as inline messages (the user can fix
 *       the cause or read the limit). Other errors fall back to a generic
 *       toast.</li>
 * </ul>
 *
 * <p>Form state is reset every time the modal opens — using {@code open}
 * as the {@code key} of the inner form ensures the textarea and dropdown
 * reset between sessions without manual {@code useEffect} plumbing.
 */
export function ReportGroupModal({
  groupPublicId,
  open,
  onClose,
}: ReportGroupModalProps) {
  if (!open) return null;
  return (
    <Modal open={open} title="Report group" onClose={onClose}>
      <ReportGroupForm groupPublicId={groupPublicId} onClose={onClose} />
    </Modal>
  );
}

interface ReportGroupFormProps {
  groupPublicId: string;
  onClose: () => void;
}

function ReportGroupForm({ groupPublicId, onClose }: ReportGroupFormProps) {
  const submit = useSubmitGroupReport(groupPublicId);
  const toast = useToast();
  const [reason, setReason] = useState<RealtyGroupReportReason>(
    "FRAUDULENT_LISTINGS",
  );
  const [details, setDetails] = useState("");
  const [touched, setTouched] = useState(false);
  const [inlineError, setInlineError] = useState<string | null>(null);

  const trimmedLength = details.trim().length;
  const detailsTooShort = trimmedLength < DETAILS_MIN;
  const detailsError =
    touched && detailsTooShort
      ? `Please share at least ${DETAILS_MIN} characters of detail.`
      : undefined;

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setTouched(true);
    setInlineError(null);
    if (detailsTooShort) return;
    try {
      await submit.mutateAsync({ reason, details: details.trim() });
      toast.success("Report submitted");
      onClose();
    } catch (err) {
      if (isApiError(err)) {
        const code = (err.problem as { code?: string }).code;
        if (err.status === 409 && code === "ALREADY_REPORTED") {
          setInlineError("You already have an open report on this group.");
          return;
        }
        if (err.status === 409 && code === "CANNOT_REPORT_OWN_GROUP") {
          setInlineError("You can't report a group you're a member of.");
          return;
        }
        if (err.status === 429 && code === "REPORT_RATE_LIMITED") {
          setInlineError(
            "You've reached your daily report limit. Try again tomorrow.",
          );
          return;
        }
      }
      toast.error("Couldn't submit your report. Please try again.");
    }
  }

  return (
    <form
      onSubmit={onSubmit}
      className="flex flex-col gap-4"
      aria-label="Report group"
      data-testid="report-group-form"
    >
      <div className="flex flex-col gap-1">
        <label
          htmlFor="report-group-reason"
          className="text-xs font-medium text-fg"
        >
          Reason <span className="text-danger">*</span>
        </label>
        <select
          id="report-group-reason"
          value={reason}
          onChange={(e) => setReason(e.target.value as RealtyGroupReportReason)}
          data-testid="report-group-reason"
          className="w-full rounded-lg bg-bg-muted px-4 py-2.5 text-fg ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand text-sm"
        >
          {REASONS.map((r) => (
            <option key={r} value={r}>
              {REASON_LABELS[r]}
            </option>
          ))}
        </select>
      </div>

      <div className="flex flex-col gap-1">
        <label
          htmlFor="report-group-details"
          className="text-xs font-medium text-fg"
        >
          Details <span className="text-danger">*</span>
        </label>
        <textarea
          id="report-group-details"
          rows={5}
          value={details}
          onChange={(e) => setDetails(e.target.value.slice(0, DETAILS_MAX))}
          placeholder="Describe what you observed and why it warrants admin review."
          data-testid="report-group-details"
          aria-invalid={detailsError ? true : undefined}
          aria-describedby="report-group-details-counter"
          className="w-full resize-y rounded-lg bg-bg-muted px-4 py-3 text-fg placeholder:text-fg-muted ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand text-sm"
        />
        <div className="flex items-center justify-between gap-2">
          {detailsError ? (
            <span
              className="text-[11px] font-medium text-danger"
              data-testid="report-group-details-error"
            >
              {detailsError}
            </span>
          ) : (
            <span className="text-[11px] text-fg-muted">
              {DETAILS_MIN}-{DETAILS_MAX} characters.
            </span>
          )}
          <span
            id="report-group-details-counter"
            className="text-[11px] font-medium text-fg-muted"
          >
            {details.length} / {DETAILS_MAX}
          </span>
        </div>
      </div>

      <div className="rounded-lg bg-bg-muted px-4 py-3 text-xs text-fg-muted">
        Your identity is shared with admin reviewers. The group leader is never
        shown who reported. Frivolous reports are tracked.
      </div>

      {inlineError && (
        <div
          role="alert"
          data-testid="report-group-inline-error"
          className="rounded-lg bg-danger-bg px-4 py-3 text-sm text-danger"
        >
          {inlineError}
        </div>
      )}

      <div className="flex justify-end gap-3">
        <Button
          type="button"
          variant="secondary"
          size="sm"
          onClick={onClose}
          disabled={submit.isPending}
          data-testid="report-group-cancel"
        >
          Cancel
        </Button>
        <Button
          type="submit"
          variant="primary"
          size="sm"
          loading={submit.isPending}
          disabled={submit.isPending}
          data-testid="report-group-submit"
        >
          Submit report
        </Button>
      </div>
    </form>
  );
}
