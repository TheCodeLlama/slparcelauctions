"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/Button";
import { Modal } from "@/components/ui/Modal";
import { isApiError } from "@/lib/api";
import { useIssueGroupSuspension } from "@/hooks/realty/useIssueGroupSuspension";
import type { SuspensionReason } from "@/types/realty";

export interface AdminGroupSuspensionModalProps {
  open: boolean;
  groupPublicId: string;
  onClose: () => void;
}

const REASON_OPTIONS: { value: SuspensionReason; label: string }[] = [
  { value: "FRAUD", label: "Fraud" },
  { value: "REPORTS_RESOLVED_AGAINST", label: "Reports resolved against" },
  { value: "TOS_VIOLATION", label: "TOS violation" },
  { value: "ABUSE", label: "Abuse" },
  { value: "OTHER", label: "Other" },
];

type DurationMode = "TIMED" | "PERMANENT";

/**
 * Realty Groups: F — modal for issuing a new realty-group suspension or
 * permanent ban from the admin detail page.
 *
 * <p>Form shape:
 * <ul>
 *   <li>Reason dropdown ({@link SuspensionReason}) — required.</li>
 *   <li>Notes textarea — required (suspension audit trail).</li>
 *   <li>Duration toggle — timed (with a datetime-local expiresAt) or
 *       permanent ban.</li>
 *   <li>"Also bulk-suspend active listings" checkbox — opts into the
 *       cascading bulk-listing pipeline.</li>
 * </ul>
 *
 * <p>On submit the {@link useIssueGroupSuspension} mutation fires; the
 * underlying hook invalidates the suspension list so the parent Suspensions
 * tab re-renders against the freshly-issued row. Errors surface inline.
 */
export function AdminGroupSuspensionModal({
  open,
  groupPublicId,
  onClose,
}: AdminGroupSuspensionModalProps) {
  const [reason, setReason] = useState<SuspensionReason>("TOS_VIOLATION");
  const [notes, setNotes] = useState("");
  const [mode, setMode] = useState<DurationMode>("TIMED");
  const [expiresAt, setExpiresAt] = useState("");
  const [bulkSuspend, setBulkSuspend] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const issue = useIssueGroupSuspension(groupPublicId);

  useEffect(() => {
    if (!open) {
      setReason("TOS_VIOLATION");
      setNotes("");
      setMode("TIMED");
      setExpiresAt("");
      setBulkSuspend(false);
      setError(null);
    }
  }, [open]);

  function handleSubmit() {
    setError(null);
    if (notes.trim().length === 0) {
      setError("Notes are required.");
      return;
    }
    if (mode === "TIMED" && expiresAt.trim().length === 0) {
      setError("Pick an expiry timestamp or switch to permanent ban.");
      return;
    }
    const expiresIso =
      mode === "PERMANENT" ? null : new Date(expiresAt).toISOString();
    if (mode === "TIMED" && (!expiresIso || Number.isNaN(new Date(expiresIso).getTime()))) {
      setError("Invalid expiry timestamp.");
      return;
    }
    issue.mutate(
      {
        reason,
        notes: notes.trim(),
        expiresAt: expiresIso,
        bulkSuspendListings: bulkSuspend,
      },
      {
        onSuccess: () => onClose(),
        onError: (err) => {
          if (isApiError(err)) {
            setError(
              (err.problem.detail as string | undefined) ??
                (err.problem.title as string | undefined) ??
                err.message,
            );
          } else if (err instanceof Error) {
            setError(err.message);
          } else {
            setError("Could not issue suspension.");
          }
        },
      },
    );
  }

  return (
    <Modal
      open={open}
      title="Issue suspension or ban"
      onClose={onClose}
      footer={
        <>
          <Button
            variant="secondary"
            onClick={onClose}
            disabled={issue.isPending}
          >
            Cancel
          </Button>
          <Button
            variant="destructive"
            onClick={handleSubmit}
            loading={issue.isPending}
            data-testid="admin-group-suspension-modal-submit"
          >
            Issue
          </Button>
        </>
      }
    >
      <div
        className="flex flex-col gap-3"
        data-testid="admin-group-suspension-modal"
      >
        <label className="flex flex-col gap-1 text-xs text-fg-muted">
          Reason
          <select
            value={reason}
            onChange={(e) => setReason(e.target.value as SuspensionReason)}
            className="rounded-lg bg-bg-subtle px-3 py-2 text-sm text-fg ring-1 ring-transparent focus:outline-none focus:ring-brand"
            data-testid="admin-group-suspension-modal-reason"
          >
            {REASON_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </label>

        <label className="flex flex-col gap-1 text-xs text-fg-muted">
          Notes
          <textarea
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            rows={4}
            className="rounded-lg bg-bg-subtle px-3 py-2 text-sm text-fg ring-1 ring-transparent focus:outline-none focus:ring-brand"
            data-testid="admin-group-suspension-modal-notes"
            placeholder="What did this group do? Linked reports, fraud flags, etc."
          />
        </label>

        <fieldset className="flex flex-col gap-2">
          <legend className="text-xs text-fg-muted">Duration</legend>
          <label className="flex items-center gap-2 text-sm text-fg">
            <input
              type="radio"
              name="duration"
              value="TIMED"
              checked={mode === "TIMED"}
              onChange={() => setMode("TIMED")}
              data-testid="admin-group-suspension-modal-mode-timed"
            />
            Timed suspension
          </label>
          {mode === "TIMED" && (
            <label className="ml-6 flex flex-col gap-1 text-xs text-fg-muted">
              Expires at
              <input
                type="datetime-local"
                value={expiresAt}
                onChange={(e) => setExpiresAt(e.target.value)}
                className="rounded-lg bg-bg-subtle px-3 py-2 text-sm text-fg ring-1 ring-transparent focus:outline-none focus:ring-brand"
                data-testid="admin-group-suspension-modal-expires-at"
              />
            </label>
          )}
          <label className="flex items-center gap-2 text-sm text-fg">
            <input
              type="radio"
              name="duration"
              value="PERMANENT"
              checked={mode === "PERMANENT"}
              onChange={() => setMode("PERMANENT")}
              data-testid="admin-group-suspension-modal-mode-permanent"
            />
            Permanent ban
          </label>
        </fieldset>

        <label className="flex items-center gap-2 text-xs text-fg">
          <input
            type="checkbox"
            checked={bulkSuspend}
            onChange={(e) => setBulkSuspend(e.target.checked)}
            data-testid="admin-group-suspension-modal-bulk-suspend"
          />
          Also bulk-suspend active listings
        </label>

        {error && (
          <p
            className="text-xs text-danger"
            data-testid="admin-group-suspension-modal-error"
          >
            {error}
          </p>
        )}
      </div>
    </Modal>
  );
}
