"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Modal } from "@/components/ui/Modal";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { isApiError } from "@/lib/api";
import { useSlGroupAckDrift } from "@/hooks/realty/useSlGroupAckDrift";
import { useSlGroupForceUnregister } from "@/hooks/realty/useSlGroupForceUnregister";
import { useSlGroupRecheck } from "@/hooks/realty/useSlGroupRecheck";
import type {
  AdminRealtyGroupSlGroup,
  SlGroupDriftReason,
} from "@/types/realty";

export interface AdminSlGroupDriftRowProps {
  groupPublicId: string;
  row: AdminRealtyGroupSlGroup;
}

const DRIFT_LABEL: Record<SlGroupDriftReason, string> = {
  FOUNDER_CHANGED: "Founder changed",
  GROUP_NOT_FOUND: "Group not found",
  FETCH_FAILED_REPEATEDLY: "Fetch failed repeatedly",
};

function driftLabel(value: SlGroupDriftReason | string | null | undefined): string {
  if (!value) return "Unknown";
  if (typeof value === "string" && value in DRIFT_LABEL) {
    return DRIFT_LABEL[value as SlGroupDriftReason];
  }
  return String(value);
}

function truncateUuid(uuid: string | null | undefined): string {
  if (!uuid) return "(none)";
  if (uuid.length <= 12) return uuid;
  return `${uuid.slice(0, 8)}…${uuid.slice(-4)}`;
}

/**
 * Realty Groups: F — single-row admin view of one
 * {@link AdminRealtyGroupSlGroup} registration, with the drift status and
 * per-row actions (recheck / ack drift / force unregister).
 *
 * <p>Drift indicators:
 * <ul>
 *   <li>{@code driftDetectedAt} non-null → warning pill with drift reason.</li>
 *   <li>{@code unregisteredAt} non-null → "Unregistered" pill (terminal).</li>
 *   <li>Otherwise → green "Healthy" pill if verified.</li>
 * </ul>
 *
 * <p>Actions:
 * <ul>
 *   <li>"Recheck" → synchronous reverify pass.</li>
 *   <li>"Ack drift" → only visible when drift is detected; clears the
 *       drift fields and rolls the founder snapshot forward.</li>
 *   <li>"Force unregister" → opens a confirmation modal (reason required);
 *       cascades any in-flight group-sale listings into the bulk-suspend
 *       pipeline.</li>
 * </ul>
 */
export function AdminSlGroupDriftRow({
  groupPublicId,
  row,
}: AdminSlGroupDriftRowProps) {
  const recheck = useSlGroupRecheck(groupPublicId);
  const ack = useSlGroupAckDrift(groupPublicId);
  const force = useSlGroupForceUnregister(groupPublicId);

  const [forceOpen, setForceOpen] = useState(false);
  const [forceReason, setForceReason] = useState("");
  const [forceError, setForceError] = useState<string | null>(null);
  const [rowError, setRowError] = useState<string | null>(null);

  const isUnregistered = !!row.unregisteredAt;
  const isDrifted = !!row.driftDetectedAt && !row.driftAcknowledgedAt;

  function handleRecheck() {
    setRowError(null);
    recheck.mutate(row.publicId, {
      onError: (err) => {
        if (isApiError(err)) {
          setRowError(
            (err.problem.detail as string | undefined) ??
              (err.problem.title as string | undefined) ??
              err.message,
          );
        } else if (err instanceof Error) {
          setRowError(err.message);
        } else {
          setRowError("Recheck failed.");
        }
      },
    });
  }

  function handleAck() {
    setRowError(null);
    ack.mutate(
      { slGroupPublicId: row.publicId, body: { notes: undefined } },
      {
        onError: (err) => {
          if (isApiError(err)) {
            setRowError(
              (err.problem.detail as string | undefined) ??
                (err.problem.title as string | undefined) ??
                err.message,
            );
          } else if (err instanceof Error) {
            setRowError(err.message);
          } else {
            setRowError("Ack drift failed.");
          }
        },
      },
    );
  }

  function handleForceConfirm() {
    setForceError(null);
    if (forceReason.trim().length === 0) {
      setForceError("Reason is required.");
      return;
    }
    force.mutate(
      {
        slGroupPublicId: row.publicId,
        body: { reason: forceReason.trim() },
        force: true,
      },
      {
        onSuccess: () => {
          setForceOpen(false);
          setForceReason("");
        },
        onError: (err) => {
          if (isApiError(err)) {
            setForceError(
              (err.problem.detail as string | undefined) ??
                (err.problem.title as string | undefined) ??
                err.message,
            );
          } else if (err instanceof Error) {
            setForceError(err.message);
          } else {
            setForceError("Force-unregister failed.");
          }
        },
      },
    );
  }

  return (
    <>
      <tr
        className="border-t border-border-subtle align-top"
        data-testid={`admin-sl-group-row-${row.publicId}`}
      >
        <td className="py-2 pr-3">
          {isUnregistered ? (
            <StatusBadge
              tone="default"
              data-testid={`admin-sl-group-status-unregistered-${row.publicId}`}
            >
              Unregistered
            </StatusBadge>
          ) : isDrifted ? (
            <StatusBadge
              tone="warning"
              data-testid={`admin-sl-group-status-drift-${row.publicId}`}
            >
              Drift: {driftLabel(row.driftReason)}
            </StatusBadge>
          ) : row.verified ? (
            <StatusBadge
              tone="success"
              data-testid={`admin-sl-group-status-healthy-${row.publicId}`}
            >
              Healthy
            </StatusBadge>
          ) : (
            <StatusBadge
              tone="warning"
              data-testid={`admin-sl-group-status-pending-${row.publicId}`}
            >
              Pending
            </StatusBadge>
          )}
        </td>
        <td className="py-2 pr-3 text-xs text-fg">
          <div className="font-medium" data-testid="admin-sl-group-name">
            {row.slGroupName ?? "(name pending)"}
          </div>
          <div
            className="font-mono text-xs text-fg-muted"
            title={row.slGroupUuid}
          >
            {truncateUuid(row.slGroupUuid)}
          </div>
        </td>
        <td className="py-2 pr-3 text-xs text-fg-muted">
          <div data-testid="admin-sl-group-founder">
            Founder: {truncateUuid(row.founderAvatarUuid)}
          </div>
          {row.currentFounderUuid &&
            row.currentFounderUuid !== row.founderAvatarUuid && (
              <div
                className="text-warning"
                data-testid="admin-sl-group-current-founder"
              >
                Current: {truncateUuid(row.currentFounderUuid)}
              </div>
            )}
        </td>
        <td className="py-2 pr-3 text-xs text-fg-muted">
          {row.consecutiveFetchFailures > 0
            ? `${row.consecutiveFetchFailures} fetch failure${row.consecutiveFetchFailures === 1 ? "" : "s"}`
            : "0 failures"}
        </td>
        <td className="py-2 text-right">
          <div className="flex flex-col items-end gap-1">
            <div className="flex flex-wrap justify-end gap-2">
              <Button
                type="button"
                size="sm"
                variant="secondary"
                onClick={handleRecheck}
                loading={recheck.isPending}
                disabled={isUnregistered}
                data-testid={`admin-sl-group-recheck-${row.publicId}`}
              >
                Recheck
              </Button>
              {isDrifted && (
                <Button
                  type="button"
                  size="sm"
                  variant="primary"
                  onClick={handleAck}
                  loading={ack.isPending}
                  data-testid={`admin-sl-group-ack-${row.publicId}`}
                >
                  Ack drift
                </Button>
              )}
              <Button
                type="button"
                size="sm"
                variant="destructive"
                onClick={() => setForceOpen(true)}
                disabled={isUnregistered}
                data-testid={`admin-sl-group-force-${row.publicId}`}
              >
                Force unregister
              </Button>
            </div>
            {rowError && (
              <p
                className="text-xs text-danger"
                data-testid={`admin-sl-group-row-error-${row.publicId}`}
              >
                {rowError}
              </p>
            )}
          </div>
        </td>
      </tr>

      <Modal
        open={forceOpen}
        title="Force-unregister this SL group?"
        onClose={() => {
          setForceOpen(false);
          setForceReason("");
          setForceError(null);
        }}
        footer={
          <>
            <Button
              variant="secondary"
              onClick={() => {
                setForceOpen(false);
                setForceReason("");
                setForceError(null);
              }}
              disabled={force.isPending}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={handleForceConfirm}
              loading={force.isPending}
              data-testid={`admin-sl-group-force-confirm-${row.publicId}`}
            >
              Force unregister
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-3">
          <p className="text-sm text-fg">
            This bypasses the active-listings gate. Any in-flight listings
            under this SL group are cascaded into the bulk-suspend pipeline
            (48-hour auto-cancel timer).
          </p>
          <label className="flex flex-col gap-1 text-xs text-fg-muted">
            Reason
            <textarea
              value={forceReason}
              onChange={(e) => setForceReason(e.target.value)}
              rows={3}
              className="rounded-lg bg-bg-subtle px-3 py-2 text-sm text-fg ring-1 ring-transparent focus:outline-none focus:ring-brand"
              data-testid={`admin-sl-group-force-reason-${row.publicId}`}
            />
          </label>
          {forceError && (
            <p
              className="text-xs text-danger"
              data-testid={`admin-sl-group-force-error-${row.publicId}`}
            >
              {forceError}
            </p>
          )}
        </div>
      </Modal>
    </>
  );
}
