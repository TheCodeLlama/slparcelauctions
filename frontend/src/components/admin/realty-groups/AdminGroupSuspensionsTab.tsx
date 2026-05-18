"use client";

import { useMemo, useState } from "react";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { Modal } from "@/components/ui/Modal";
import { GroupStatusPill } from "@/components/realty/GroupStatusPill";
import { useGroupSuspensions } from "@/hooks/realty/useGroupSuspensions";
import { useLiftGroupSuspension } from "@/hooks/realty/useLiftGroupSuspension";
import type {
  RealtyGroupSuspension,
  SuspensionReason,
  SuspensionStatus,
} from "@/types/realty";
import { AdminGroupSuspensionModal } from "./AdminGroupSuspensionModal";

export interface AdminGroupSuspensionsTabProps {
  groupPublicId: string;
}

const REASON_LABEL: Record<SuspensionReason, string> = {
  FRAUD: "Fraud",
  REPORTS_RESOLVED_AGAINST: "Reports resolved against",
  TOS_VIOLATION: "TOS violation",
  ABUSE: "Abuse",
  OTHER: "Other",
};

const STATUS_LABEL: Record<SuspensionStatus, string> = {
  ACTIVE_TIMED: "Active (timed)",
  ACTIVE_PERMANENT: "Active (permanent)",
  LIFTED: "Lifted",
  EXPIRED: "Expired",
};

function reasonLabel(value: SuspensionReason | string | null | undefined): string {
  if (!value) return "Unknown";
  if (value in REASON_LABEL) return REASON_LABEL[value as SuspensionReason];
  return String(value);
}

function statusLabel(value: SuspensionStatus | string | null | undefined): string {
  if (!value) return "Unknown";
  if (value in STATUS_LABEL) return STATUS_LABEL[value as SuspensionStatus];
  return String(value);
}

function formatTimestamp(iso: string | null | undefined): string {
  if (!iso) return "(none)";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "(none)";
  return d.toLocaleString();
}

function pickActive(
  rows: readonly RealtyGroupSuspension[],
): RealtyGroupSuspension | null {
  return (
    rows.find(
      (r) => r.status === "ACTIVE_TIMED" || r.status === "ACTIVE_PERMANENT",
    ) ?? null
  );
}

/**
 * Realty Groups: F — admin "Suspensions" tab on the group detail page.
 *
 * <p>Lists the suspension history for one group, surfacing the current
 * status pill, an "Issue suspension/ban" button that opens the modal, and a
 * per-row "Lift" affordance for any row whose status is still
 * {@code ACTIVE_*}. Mutations route through the shared hooks; the tab
 * re-renders against the post-mutation cache as soon as TanStack
 * invalidates the query.
 *
 * <p>Defensive coercion: each row's {@code reason} / {@code status} is run
 * through {@link reasonLabel} / {@link statusLabel} so an unexpected enum
 * value (e.g. new backend reason added before the frontend ships) renders
 * the raw string rather than crashing.
 */
export function AdminGroupSuspensionsTab({
  groupPublicId,
}: AdminGroupSuspensionsTabProps) {
  const { data: rows, isLoading, isError } = useGroupSuspensions(groupPublicId);
  const lift = useLiftGroupSuspension(groupPublicId);
  const [issueOpen, setIssueOpen] = useState(false);
  const [liftTarget, setLiftTarget] = useState<RealtyGroupSuspension | null>(
    null,
  );
  const [liftNotes, setLiftNotes] = useState("");
  const [liftBulk, setLiftBulk] = useState(false);

  const safeRows = useMemo<RealtyGroupSuspension[]>(
    () => (Array.isArray(rows) ? rows : []),
    [rows],
  );
  const active = useMemo(() => pickActive(safeRows), [safeRows]);

  function handleLiftConfirm() {
    if (!liftTarget) return;
    lift.mutate(
      {
        suspensionPublicId: liftTarget.publicId,
        body: { notes: liftNotes.trim() || undefined, bulkReinstateListings: liftBulk },
      },
      {
        onSuccess: () => {
          setLiftTarget(null);
          setLiftNotes("");
          setLiftBulk(false);
        },
      },
    );
  }

  return (
    <Card data-testid="admin-group-suspensions-tab">
      <Card.Header>
        <div className="flex items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <h2 className="text-sm font-semibold tracking-tight">
              Suspensions
            </h2>
            <GroupStatusPill
              status={active?.status ?? null}
              expiresAt={active?.expiresAt}
              reason={active?.notes}
            />
          </div>
          <Button
            type="button"
            variant="destructive"
            size="sm"
            onClick={() => setIssueOpen(true)}
            data-testid="admin-group-suspensions-issue-button"
          >
            Issue suspension / ban
          </Button>
        </div>
      </Card.Header>
      <Card.Body>
        {isLoading && (
          <div data-testid="admin-group-suspensions-loading">
            <LoadingSpinner label="Loading suspension history…" />
          </div>
        )}
        {isError && (
          <p
            className="text-xs text-danger"
            data-testid="admin-group-suspensions-error"
          >
            Could not load suspension history. Refresh to retry.
          </p>
        )}
        {!isLoading && !isError && safeRows.length === 0 && (
          <p
            className="text-xs text-fg-muted"
            data-testid="admin-group-suspensions-empty"
          >
            No suspensions on record for this group.
          </p>
        )}
        {!isLoading && !isError && safeRows.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs text-fg-muted">
                  <th className="py-2 pr-3 font-medium">Status</th>
                  <th className="py-2 pr-3 font-medium">Reason</th>
                  <th className="py-2 pr-3 font-medium">Issued</th>
                  <th className="py-2 pr-3 font-medium">Expires</th>
                  <th className="py-2 pr-3 font-medium">Issued by</th>
                  <th className="py-2 pr-3 font-medium">Notes</th>
                  <th className="py-2 font-medium text-right">Actions</th>
                </tr>
              </thead>
              <tbody data-testid="admin-group-suspensions-table">
                {safeRows.map((row) => {
                  const canLift =
                    row.status === "ACTIVE_TIMED" ||
                    row.status === "ACTIVE_PERMANENT";
                  return (
                    <tr
                      key={row.publicId}
                      className="border-t border-border-subtle align-top"
                      data-testid={`admin-group-suspension-row-${row.publicId}`}
                    >
                      <td className="py-2 pr-3 text-xs text-fg-muted">
                        {statusLabel(row.status)}
                      </td>
                      <td className="py-2 pr-3 text-xs text-fg">
                        {reasonLabel(row.reason)}
                      </td>
                      <td className="py-2 pr-3 text-xs text-fg-muted">
                        {formatTimestamp(row.issuedAt)}
                      </td>
                      <td className="py-2 pr-3 text-xs text-fg-muted">
                        {row.expiresAt ? formatTimestamp(row.expiresAt) : "Permanent"}
                      </td>
                      <td className="py-2 pr-3 text-xs text-fg-muted">
                        {row.issuedByAdmin?.displayName ?? "Unknown"}
                      </td>
                      <td className="py-2 pr-3 text-xs text-fg">
                        <span className="block max-w-md truncate" title={row.notes ?? ""}>
                          {row.notes ?? ""}
                        </span>
                      </td>
                      <td className="py-2 text-right">
                        {canLift ? (
                          <Button
                            type="button"
                            variant="secondary"
                            size="sm"
                            onClick={() => {
                              setLiftTarget(row);
                              setLiftNotes("");
                              setLiftBulk(false);
                            }}
                            data-testid={`admin-group-suspension-lift-${row.publicId}`}
                          >
                            Lift
                          </Button>
                        ) : (
                          <span className="text-xs text-fg-muted">(none)</span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card.Body>

      <AdminGroupSuspensionModal
        open={issueOpen}
        groupPublicId={groupPublicId}
        onClose={() => setIssueOpen(false)}
      />

      <Modal
        open={!!liftTarget}
        title="Lift suspension?"
        onClose={() => {
          setLiftTarget(null);
          setLiftNotes("");
          setLiftBulk(false);
        }}
        footer={
          <>
            <Button
              variant="secondary"
              onClick={() => {
                setLiftTarget(null);
                setLiftNotes("");
                setLiftBulk(false);
              }}
              disabled={lift.isPending}
            >
              Cancel
            </Button>
            <Button
              variant="primary"
              onClick={handleLiftConfirm}
              loading={lift.isPending}
              data-testid="admin-group-suspension-lift-confirm"
            >
              Lift
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-3">
          <p className="text-sm text-fg">
            This removes the suspension immediately. The group regains the
            ability to operate.
          </p>
          <label className="flex flex-col gap-1 text-xs text-fg-muted">
            Notes (optional)
            <textarea
              value={liftNotes}
              onChange={(e) => setLiftNotes(e.target.value)}
              rows={3}
              className="rounded-lg bg-bg-subtle px-3 py-2 text-sm text-fg ring-1 ring-transparent focus:outline-none focus:ring-brand"
              data-testid="admin-group-suspension-lift-notes"
            />
          </label>
          <label className="flex items-center gap-2 text-xs text-fg">
            <input
              type="checkbox"
              checked={liftBulk}
              onChange={(e) => setLiftBulk(e.target.checked)}
              data-testid="admin-group-suspension-lift-bulk"
            />
            Also reinstate bulk-suspended listings
          </label>
        </div>
      </Modal>
    </Card>
  );
}
