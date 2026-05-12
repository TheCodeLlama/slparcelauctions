"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { Modal } from "@/components/ui/Modal";
import { isApiError } from "@/lib/api";
import {
  useBulkReinstateListings,
  useBulkSuspendListings,
} from "@/hooks/realty/useBulkSuspendListings";

export interface AdminGroupBulkListingsTabProps {
  groupPublicId: string;
}

type ConfirmKind = "SUSPEND" | "REINSTATE" | null;

/**
 * Realty Groups: F — admin "Bulk listings" tab on the group detail page.
 *
 * <p>Surfaces the two cascade actions:
 * <ul>
 *   <li>"Suspend all active listings" → triggers the bulk-suspend pipeline
 *       (active listings on this group are force-suspended with a 48h
 *       auto-cancel timer).</li>
 *   <li>"Reinstate all" → lifts every {@code ADMIN_GROUP_BULK}-cause
 *       listing suspension on this group.</li>
 * </ul>
 *
 * <p>Both actions are gated behind a confirmation modal — accidental clicks
 * shouldn't ripple across every active listing on a group. The result
 * payload is surfaced inline so admins see "suspended N" / "reinstated N"
 * without bouncing to another tab.
 *
 * <p>Note: per spec there is no dedicated GET endpoint for the bulk-cause
 * listing-suspension cascade view — the existing listings table on the
 * detail page already lists per-listing suspension state. This tab focuses
 * on the cascade actions themselves; the underlying state lives on
 * {@code listing_suspensions}.
 */
export function AdminGroupBulkListingsTab({
  groupPublicId,
}: AdminGroupBulkListingsTabProps) {
  const suspend = useBulkSuspendListings(groupPublicId);
  const reinstate = useBulkReinstateListings(groupPublicId);

  const [confirm, setConfirm] = useState<ConfirmKind>(null);
  const [reason, setReason] = useState("");
  const [notes, setNotes] = useState("");
  const [lastSuspended, setLastSuspended] = useState<number | null>(null);
  const [lastReinstated, setLastReinstated] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  function closeConfirm() {
    setConfirm(null);
    setReason("");
    setNotes("");
    setError(null);
  }

  function handleSuspendConfirm() {
    setError(null);
    if (reason.trim().length === 0) {
      setError("Reason is required.");
      return;
    }
    suspend.mutate(
      { reason: reason.trim(), notes: notes.trim() || undefined },
      {
        onSuccess: (res) => {
          setLastSuspended(res?.suspendedCount ?? 0);
          setLastReinstated(null);
          closeConfirm();
        },
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
            setError("Suspend failed.");
          }
        },
      },
    );
  }

  function handleReinstateConfirm() {
    setError(null);
    reinstate.mutate(
      { notes: notes.trim() || undefined },
      {
        onSuccess: (res) => {
          setLastReinstated(res?.reinstatedCount ?? 0);
          setLastSuspended(null);
          closeConfirm();
        },
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
            setError("Reinstate failed.");
          }
        },
      },
    );
  }

  return (
    <Card data-testid="admin-group-bulk-listings-tab">
      <Card.Header>
        <h2 className="text-sm font-semibold tracking-tight">Bulk listings</h2>
      </Card.Header>
      <Card.Body>
        <p className="text-xs text-fg-muted mb-3">
          Cascade-suspend every active listing on this group, or reinstate
          every bulk-suspended listing. Suspended listings auto-cancel after
          48 hours; admins must reinstate before then to avoid loss.
        </p>

        <div className="flex flex-wrap gap-2">
          <Button
            type="button"
            variant="destructive"
            size="sm"
            onClick={() => setConfirm("SUSPEND")}
            data-testid="admin-group-bulk-suspend-button"
          >
            Suspend all active listings
          </Button>
          <Button
            type="button"
            variant="secondary"
            size="sm"
            onClick={() => setConfirm("REINSTATE")}
            data-testid="admin-group-bulk-reinstate-button"
          >
            Reinstate all
          </Button>
        </div>

        {lastSuspended !== null && (
          <p
            className="mt-3 text-xs text-success"
            data-testid="admin-group-bulk-suspend-result"
          >
            Suspended {lastSuspended} listing{lastSuspended === 1 ? "" : "s"}.
          </p>
        )}
        {lastReinstated !== null && (
          <p
            className="mt-3 text-xs text-success"
            data-testid="admin-group-bulk-reinstate-result"
          >
            Reinstated {lastReinstated} listing{lastReinstated === 1 ? "" : "s"}.
          </p>
        )}
      </Card.Body>

      <Modal
        open={confirm === "SUSPEND"}
        title="Bulk-suspend all listings?"
        onClose={closeConfirm}
        footer={
          <>
            <Button
              variant="secondary"
              onClick={closeConfirm}
              disabled={suspend.isPending}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={handleSuspendConfirm}
              loading={suspend.isPending}
              data-testid="admin-group-bulk-suspend-confirm"
            >
              Suspend all
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-3">
          <p className="text-sm text-fg">
            Every active listing on this group will be force-suspended with a
            48-hour auto-cancel timer. Bidders are notified; existing bids are
            retained until the timer expires.
          </p>
          <label className="flex flex-col gap-1 text-xs text-fg-muted">
            Reason
            <input
              type="text"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              className="rounded-lg bg-bg-subtle px-3 py-2 text-sm text-fg ring-1 ring-transparent focus:outline-none focus:ring-brand"
              data-testid="admin-group-bulk-suspend-reason"
            />
          </label>
          <label className="flex flex-col gap-1 text-xs text-fg-muted">
            Notes (optional)
            <textarea
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              rows={3}
              className="rounded-lg bg-bg-subtle px-3 py-2 text-sm text-fg ring-1 ring-transparent focus:outline-none focus:ring-brand"
              data-testid="admin-group-bulk-suspend-notes"
            />
          </label>
          {error && (
            <p
              className="text-xs text-danger"
              data-testid="admin-group-bulk-suspend-error"
            >
              {error}
            </p>
          )}
        </div>
      </Modal>

      <Modal
        open={confirm === "REINSTATE"}
        title="Reinstate every bulk-suspended listing?"
        onClose={closeConfirm}
        footer={
          <>
            <Button
              variant="secondary"
              onClick={closeConfirm}
              disabled={reinstate.isPending}
            >
              Cancel
            </Button>
            <Button
              variant="primary"
              onClick={handleReinstateConfirm}
              loading={reinstate.isPending}
              data-testid="admin-group-bulk-reinstate-confirm"
            >
              Reinstate all
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-3">
          <p className="text-sm text-fg">
            Every {`ADMIN_GROUP_BULK`}-cause suspension on this group is
            lifted. Listings whose 48-hour auto-cancel timer has not yet
            elapsed resume in their previous state.
          </p>
          <label className="flex flex-col gap-1 text-xs text-fg-muted">
            Notes (optional)
            <textarea
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              rows={3}
              className="rounded-lg bg-bg-subtle px-3 py-2 text-sm text-fg ring-1 ring-transparent focus:outline-none focus:ring-brand"
              data-testid="admin-group-bulk-reinstate-notes"
            />
          </label>
          {error && (
            <p
              className="text-xs text-danger"
              data-testid="admin-group-bulk-reinstate-error"
            >
              {error}
            </p>
          )}
        </div>
      </Modal>
    </Card>
  );
}
