"use client";

import Link from "next/link";
import { useState, type ReactNode } from "react";
import { Button } from "@/components/ui/Button";
import { Modal } from "@/components/ui/Modal";
import { useGroupReportDetail } from "@/hooks/realty/useGroupReports";
import {
  useResolveGroupReport,
  useDismissGroupReport,
} from "@/hooks/realty/useResolveGroupReport";
import type {
  AdminRealtyGroupReportDetail,
  RealtyGroupReportReason,
  RealtyGroupReportStatus,
} from "@/types/realty";
import { cn } from "@/lib/cn";

const NOTES_MAX = 1000;

const REASON_LABELS: Record<RealtyGroupReportReason, string> = {
  FRAUDULENT_LISTINGS: "Fraudulent listings",
  MISLEADING_ATTRIBUTION: "Misleading attribution",
  HARASSMENT: "Harassment",
  IMPERSONATION: "Impersonation",
  SPAM: "Spam",
  OTHER: "Other",
};

type EscalateTo = "NONE" | "SUSPEND_GROUP" | "BAN_GROUP";

function statusPillClasses(status: RealtyGroupReportStatus): string {
  switch (status) {
    case "OPEN":
      return "bg-danger-bg text-danger";
    case "RESOLVED":
      return "bg-info-bg text-info";
    case "DISMISSED":
      return "bg-bg-muted text-fg-muted";
  }
}

function formatTimestamp(iso: string | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (!Number.isFinite(d.getTime())) return iso;
  return d.toLocaleString();
}

type Props = {
  reportPublicId: string;
};

/**
 * Admin detail view for a single realty-group report.
 *
 * <ul>
 *   <li>OPEN reports: shows Resolve + Dismiss buttons, each opening a notes
 *       modal. Resolve modal carries an optional {@code escalateTo} dropdown;
 *       picking SUSPEND_GROUP or BAN_GROUP chains into an inline suspension
 *       form post-resolve (the dedicated {@code AdminGroupSuspensionModal}
 *       arrives in Task 35; this page renders a minimal in-place issuer so
 *       the resolve→suspend flow is functional today).</li>
 *   <li>Non-OPEN reports: shows the historical resolution metadata in a
 *       read-only summary block.</li>
 * </ul>
 *
 * <p>Back-to-queue link preserves the user's filter context only by
 * navigating to the queue root — Next.js doesn't expose the previous
 * search-params here and we'd rather link to a known URL than guess.
 */
export function AdminGroupReportDetailPage({ reportPublicId }: Props) {
  const { data: report, isLoading, isError } = useGroupReportDetail(reportPublicId);

  if (isLoading) {
    return (
      <div data-testid="report-detail-loading" className="space-y-2 py-4">
        <div className="h-10 w-1/3 rounded bg-bg-muted animate-pulse" />
        <div className="h-32 rounded bg-bg-muted animate-pulse" />
      </div>
    );
  }

  if (isError || !report) {
    return (
      <div className="py-8">
        <Link
          href="/admin/realty-groups/reports"
          className="text-sm text-brand underline underline-offset-2"
        >
          ← Back to queue
        </Link>
        <div
          className="mt-4 text-sm text-danger"
          data-testid="report-detail-error"
        >
          Could not load report. Refresh to retry.
        </div>
      </div>
    );
  }

  return <ReportDetail report={report} />;
}

function ReportDetail({ report }: { report: AdminRealtyGroupReportDetail }) {
  const [modal, setModal] = useState<
    | { kind: "none" }
    | { kind: "resolve" }
    | { kind: "dismiss" }
    | { kind: "escalate"; mode: EscalateTo }
  >({ kind: "none" });

  const resolve = useResolveGroupReport(report.publicId);
  const dismiss = useDismissGroupReport(report.publicId);

  const isOpen = report.status === "OPEN";

  const openResolve = () => setModal({ kind: "resolve" });
  const openDismiss = () => setModal({ kind: "dismiss" });
  const closeModal = () => setModal({ kind: "none" });

  return (
    <div>
      <Link
        href="/admin/realty-groups/reports"
        className="text-sm text-brand underline underline-offset-2"
        data-testid="back-to-queue-link"
      >
        ← Back to queue
      </Link>

      <div className="mt-3 flex items-center gap-3 flex-wrap">
        <span
          className={cn(
            "text-[10px] font-medium px-2 py-0.5 rounded-full uppercase tracking-wider",
            statusPillClasses(report.status),
          )}
          data-testid="report-detail-status"
        >
          {report.status}
        </span>
        <h1 className="text-2xl font-semibold">
          Report against{" "}
          <Link
            href={`/admin/realty-groups/${report.group.publicId}`}
            className="text-brand underline underline-offset-2"
          >
            {report.group.name}
          </Link>
        </h1>
      </div>

      <dl className="mt-6 grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2 text-sm">
        <Term>Reason</Term>
        <Detail>{REASON_LABELS[report.reason] ?? report.reason}</Detail>

        <Term>Reporter</Term>
        <Detail>
          <Link
            href={`/admin/users/${report.reporter.publicId}`}
            className="text-brand underline underline-offset-2"
          >
            {report.reporter.displayName}
          </Link>
        </Detail>

        <Term>Created</Term>
        <Detail>{formatTimestamp(report.createdAt)}</Detail>

        {report.resolvedAt && (
          <>
            <Term>Resolved at</Term>
            <Detail>{formatTimestamp(report.resolvedAt)}</Detail>
          </>
        )}

        {report.resolvedByAdmin && (
          <>
            <Term>Resolved by</Term>
            <Detail>{report.resolvedByAdmin.displayName}</Detail>
          </>
        )}
      </dl>

      <section className="mt-6">
        <h2 className="text-xs font-semibold uppercase tracking-wider text-fg-muted mb-2">
          Details
        </h2>
        <div
          className="rounded-lg bg-bg-muted px-4 py-3 text-sm whitespace-pre-wrap"
          data-testid="report-detail-body"
        >
          {report.details}
        </div>
      </section>

      {report.resolutionNotes && (
        <section className="mt-6">
          <h2 className="text-xs font-semibold uppercase tracking-wider text-fg-muted mb-2">
            Resolution notes
          </h2>
          <div
            className="rounded-lg bg-bg-muted px-4 py-3 text-sm whitespace-pre-wrap"
            data-testid="report-detail-resolution-notes"
          >
            {report.resolutionNotes}
          </div>
        </section>
      )}

      {isOpen && (
        <div className="mt-8 flex flex-wrap gap-2">
          <Button
            variant="primary"
            onClick={openResolve}
            data-testid="report-detail-resolve-btn"
          >
            Resolve
          </Button>
          <Button
            variant="secondary"
            onClick={openDismiss}
            data-testid="report-detail-dismiss-btn"
          >
            Dismiss
          </Button>
        </div>
      )}

      {modal.kind === "resolve" && (
        <ResolveModal
          onClose={closeModal}
          submitting={resolve.isPending}
          onSubmit={(payload) => {
            resolve.mutate(
              { notes: payload.notes, escalateTo: payload.escalateTo === "NONE" ? null : payload.escalateTo },
              {
                onSuccess: () => {
                  if (payload.escalateTo !== "NONE") {
                    setModal({ kind: "escalate", mode: payload.escalateTo });
                  } else {
                    closeModal();
                  }
                },
              },
            );
          }}
        />
      )}

      {modal.kind === "dismiss" && (
        <DismissModal
          onClose={closeModal}
          submitting={dismiss.isPending}
          onSubmit={(notes) => {
            dismiss.mutate(
              { notes },
              { onSuccess: closeModal },
            );
          }}
        />
      )}

      {modal.kind === "escalate" && (
        <EscalateSuspensionModal
          groupPublicId={report.group.publicId}
          groupName={report.group.name}
          mode={modal.mode}
          onClose={closeModal}
        />
      )}
    </div>
  );
}

function Term({ children }: { children: ReactNode }) {
  return (
    <dt className="text-xs font-semibold uppercase tracking-wider text-fg-muted pt-1">
      {children}
    </dt>
  );
}

function Detail({ children }: { children: ReactNode }) {
  return <dd className="text-sm text-fg">{children}</dd>;
}

// ─── Resolve modal ─────────────────────────────────────────────────────────

type ResolveModalProps = {
  onClose: () => void;
  submitting: boolean;
  onSubmit: (payload: { notes: string; escalateTo: EscalateTo }) => void;
};

function ResolveModal({ onClose, submitting, onSubmit }: ResolveModalProps) {
  const [notes, setNotes] = useState("");
  const [escalateTo, setEscalateTo] = useState<EscalateTo>("NONE");
  const notesEmpty = notes.trim().length === 0;

  return (
    <Modal
      open
      title="Resolve report"
      onClose={onClose}
      footer={
        <>
          <Button variant="tertiary" onClick={onClose} disabled={submitting}>
            Cancel
          </Button>
          <Button
            variant="primary"
            disabled={notesEmpty || submitting}
            loading={submitting}
            onClick={() => onSubmit({ notes, escalateTo })}
            data-testid="resolve-modal-submit"
          >
            Resolve
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <label className="flex flex-col gap-1">
          <span className="text-xs font-medium text-fg">
            Resolution notes <span className="text-danger">*</span>
          </span>
          <textarea
            rows={4}
            value={notes}
            disabled={submitting}
            onChange={(e) => setNotes(e.target.value.slice(0, NOTES_MAX))}
            data-testid="resolve-modal-notes"
            className="w-full resize-y rounded-lg bg-bg-muted px-4 py-3 text-fg placeholder:text-fg-muted ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand disabled:opacity-50"
            placeholder="Why is this report being resolved?"
          />
          <span className="self-end text-[11px] text-fg-muted">
            {notes.length} / {NOTES_MAX}
          </span>
        </label>

        <label className="flex flex-col gap-1">
          <span className="text-xs font-medium text-fg">
            Escalate to (optional)
          </span>
          <select
            value={escalateTo}
            disabled={submitting}
            onChange={(e) => setEscalateTo(e.target.value as EscalateTo)}
            data-testid="resolve-modal-escalate"
            className="rounded-lg bg-bg-muted px-4 py-2 text-sm ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand disabled:opacity-50"
          >
            <option value="NONE">None</option>
            <option value="SUSPEND_GROUP">Suspend group</option>
            <option value="BAN_GROUP">Ban group</option>
          </select>
          <span className="text-[11px] text-fg-muted">
            Suspending or banning opens a separate confirmation after resolve.
          </span>
        </label>
      </div>
    </Modal>
  );
}

// ─── Dismiss modal ─────────────────────────────────────────────────────────

type DismissModalProps = {
  onClose: () => void;
  submitting: boolean;
  onSubmit: (notes: string) => void;
};

function DismissModal({ onClose, submitting, onSubmit }: DismissModalProps) {
  const [notes, setNotes] = useState("");
  const notesEmpty = notes.trim().length === 0;

  return (
    <Modal
      open
      title="Dismiss report"
      onClose={onClose}
      footer={
        <>
          <Button variant="tertiary" onClick={onClose} disabled={submitting}>
            Cancel
          </Button>
          <Button
            variant="secondary"
            disabled={notesEmpty || submitting}
            loading={submitting}
            onClick={() => onSubmit(notes)}
            data-testid="dismiss-modal-submit"
          >
            Dismiss
          </Button>
        </>
      }
    >
      <p className="text-sm text-fg-muted">
        Dismissing a report bumps the reporter&apos;s dismissed-reports
        counter. Use this when the report does not require action.
      </p>
      <label className="flex flex-col gap-1 mt-2">
        <span className="text-xs font-medium text-fg">
          Dismissal notes <span className="text-danger">*</span>
        </span>
        <textarea
          rows={4}
          value={notes}
          disabled={submitting}
          onChange={(e) => setNotes(e.target.value.slice(0, NOTES_MAX))}
          data-testid="dismiss-modal-notes"
          className="w-full resize-y rounded-lg bg-bg-muted px-4 py-3 text-fg placeholder:text-fg-muted ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand disabled:opacity-50"
          placeholder="Why is this report being dismissed?"
        />
        <span className="self-end text-[11px] text-fg-muted">
          {notes.length} / {NOTES_MAX}
        </span>
      </label>
    </Modal>
  );
}

// ─── Escalate-suspension modal (stop-gap until Task 35 ships) ──────────────

type EscalateSuspensionModalProps = {
  groupPublicId: string;
  groupName: string;
  mode: EscalateTo;
  onClose: () => void;
};

/**
 * Bridge modal between resolve-with-escalation and the full-featured
 * {@code AdminGroupSuspensionModal} from Task 35. Keeps the resolve flow
 * complete today by linking the admin straight to the group's suspensions
 * tab; once Task 35 lands, swap the body for the real modal.
 */
function EscalateSuspensionModal({
  groupPublicId,
  groupName,
  mode,
  onClose,
}: EscalateSuspensionModalProps) {
  if (mode === "NONE") return null;
  const heading =
    mode === "BAN_GROUP" ? "Ban group" : "Suspend group";
  const groupHref = `/admin/realty-groups/${groupPublicId}?action=${
    mode === "BAN_GROUP" ? "ban" : "suspend"
  }`;
  return (
    <Modal
      open
      title={heading}
      onClose={onClose}
      footer={
        <>
          <Button variant="tertiary" onClick={onClose}>
            Close
          </Button>
          <Link
            href={groupHref}
            className="inline-flex items-center justify-center gap-1.5 rounded-sm border bg-brand text-white border-brand h-9 px-4 text-sm font-medium hover:bg-brand-hover hover:border-brand-hover focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
            data-testid="escalate-modal-open-suspensions"
          >
            Open suspensions tab
          </Link>
        </>
      }
    >
      <p data-testid="escalate-modal-body">
        Report resolved. Continue to the suspensions tab for{" "}
        <span className="font-semibold">{groupName}</span> to issue the{" "}
        {mode === "BAN_GROUP" ? "ban" : "suspension"}.
      </p>
    </Modal>
  );
}
