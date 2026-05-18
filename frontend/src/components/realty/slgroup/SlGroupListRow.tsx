"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { IconButton } from "@/components/ui/IconButton";
import { Modal } from "@/components/ui/Modal";
import { StatusBadge } from "@/components/ui/StatusBadge";
import {
  CheckCircle2,
  Clock,
  Copy,
  RefreshCw,
  Trash2,
} from "@/components/ui/icons";
import { isApiError } from "@/lib/api";
import type {
  RealtyGroupSlGroup,
  SlGroupVerifyMethod,
} from "@/types/realty";
import {
  useRecheckSlGroup,
  useUnregisterSlGroup,
} from "@/hooks/realty/useRegisterSlGroup";
import { formatRelativeTime } from "@/lib/time/relativeTime";

export interface SlGroupListRowProps {
  /** Realty group public UUID. */
  groupPublicId: string;
  /** The SL group registration row to render. */
  row: RealtyGroupSlGroup;
}

function truncateUuid(uuid: string): string {
  if (uuid.length <= 12) return uuid;
  return `${uuid.slice(0, 8)}…${uuid.slice(-4)}`;
}

function verifiedViaLabel(method: SlGroupVerifyMethod): string {
  switch (method) {
    case "FOUNDER_TERMINAL":
      return "Founder terminal";
  }
}

function expiryCountdown(iso: string): string {
  const expires = new Date(iso).getTime();
  if (!Number.isFinite(expires)) return "";
  const ms = expires - Date.now();
  if (ms <= 0) return "expired";
  const mins = Math.floor(ms / 60_000);
  if (mins < 60) return `expires in ${mins}m`;
  const hours = Math.floor(mins / 60);
  const remMins = mins % 60;
  return `expires in ${hours}h ${remMins}m`;
}

/**
 * Renders one SL group registration row inside the page table. Behaviour:
 *
 *  - Verified rows show a green "Verified" badge, the resolved SL group name,
 *    the verified-via label, the verified-at timestamp, and an Unregister
 *    button.
 *  - Pending rows show a warning "Pending" badge, the verification code (with
 *    a copy affordance, truncated for layout), the expiry countdown, the last
 *    poll time, and both an "Unregister" and "Check now" button.
 *
 * Mutations route through the centralised hooks
 * ({@link useRecheckSlGroup}, {@link useUnregisterSlGroup}) so the page table
 * invalidates after each action via the shared TanStack key.
 *
 * Spec §6.2.
 */
export function SlGroupListRow({ groupPublicId, row }: SlGroupListRowProps) {
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [unregisterError, setUnregisterError] = useState<string | null>(null);
  const recheck = useRecheckSlGroup(groupPublicId);
  const unregister = useUnregisterSlGroup(groupPublicId);

  const handleRecheck = () => {
    recheck.mutate(row.publicId);
  };

  const openConfirm = () => {
    setUnregisterError(null);
    setConfirmOpen(true);
  };

  const handleUnregister = async () => {
    setUnregisterError(null);
    try {
      await unregister.mutateAsync(row.publicId);
      setConfirmOpen(false);
    } catch (e) {
      if (isApiError(e)) {
        const code = e.problem.code as string | undefined;
        if (code === "REGISTERED_SL_GROUP_HAS_LISTINGS") {
          setUnregisterError(
            "This SL group has active listings on SLParcels. Cancel or close them before unregistering.",
          );
        } else {
          setUnregisterError(
            (e.problem.detail as string | undefined) ??
              (e.problem.title as string | undefined) ??
              e.message,
          );
        }
      } else {
        setUnregisterError(
          e instanceof Error ? e.message : "Unregister failed.",
        );
      }
    }
  };

  const handleCopyCode = async () => {
    if (!row.pending) return;
    try {
      await navigator.clipboard.writeText(row.pending.verificationCode);
    } catch {
      // Clipboard rejection is non-fatal; the code is also visible on screen.
    }
  };

  return (
    <>
      <tr
        className="border-b border-border-subtle last:border-b-0 hover:bg-bg-subtle"
        data-testid="sl-group-row"
        data-public-id={row.publicId}
      >
        {/* Status */}
        <td className="py-3 px-3 align-top">
          {row.verified ? (
            <StatusBadge tone="success" data-testid="status-verified">
              <CheckCircle2 className="h-3.5 w-3.5" aria-hidden="true" />
              <span>Verified</span>
            </StatusBadge>
          ) : (
            <StatusBadge tone="warning" data-testid="status-pending">
              <Clock className="h-3.5 w-3.5" aria-hidden="true" />
              <span>Pending</span>
            </StatusBadge>
          )}
        </td>

        {/* SL group name + UUID */}
        <td className="py-3 px-3 align-top">
          <div className="text-sm text-fg font-medium" data-testid="sl-group-name">
            {row.slGroupName ?? <span className="text-fg-muted">(name pending)</span>}
          </div>
          <div
            className="text-xs text-fg-muted font-mono"
            title={row.slGroupUuid}
            data-testid="sl-group-uuid"
          >
            {truncateUuid(row.slGroupUuid)}
          </div>
        </td>

        {/* Verified via */}
        <td className="py-3 px-3 align-top text-sm text-fg-muted">
          {row.verified && row.verifiedVia ? (
            <span data-testid="verified-via">
              {verifiedViaLabel(row.verifiedVia)}
            </span>
          ) : (
            <span className="text-fg-muted">(none)</span>
          )}
        </td>

        {/* Details: verified-at OR pending code + countdown + last poll */}
        <td className="py-3 px-3 align-top text-sm">
          {row.verified && row.verifiedAt && (
            <span
              className="text-xs text-fg-muted"
              title={new Date(row.verifiedAt).toLocaleString()}
              data-testid="verified-at"
            >
              Verified {formatRelativeTime(new Date(row.verifiedAt))}
            </span>
          )}
          {row.pending && (
            <div className="flex flex-col gap-1">
              <div className="flex items-center gap-1.5">
                <code
                  className="font-mono text-xs text-fg bg-bg-subtle rounded px-1.5 py-0.5 select-all"
                  data-testid="verification-code"
                >
                  {row.pending.verificationCode}
                </code>
                <IconButton
                  aria-label="Copy verification code"
                  variant="tertiary"
                  size="sm"
                  onClick={handleCopyCode}
                  data-testid="copy-verification-code"
                >
                  <Copy />
                </IconButton>
              </div>
              <span
                className="text-xs text-fg-muted"
                data-testid="expiry-countdown"
              >
                {expiryCountdown(row.pending.verificationCodeExpiresAt)}
              </span>
              <span className="text-xs text-fg-muted" data-testid="last-polled">
                {row.pending.lastPolledAt
                  ? `Last checked ${formatRelativeTime(new Date(row.pending.lastPolledAt))}`
                  : "Not yet checked"}
              </span>
            </div>
          )}
        </td>

        {/* Actions */}
        <td className="py-3 px-3 align-top">
          <div className="flex items-center justify-end gap-2">
            {!row.verified && (
              <Button
                variant="secondary"
                size="sm"
                leftIcon={<RefreshCw className="h-3.5 w-3.5" />}
                onClick={handleRecheck}
                loading={recheck.isPending}
                data-testid="recheck-button"
              >
                Check now
              </Button>
            )}
            <Button
              variant="tertiary"
              size="sm"
              leftIcon={<Trash2 className="h-3.5 w-3.5" />}
              onClick={openConfirm}
              data-testid="unregister-button"
            >
              Unregister
            </Button>
          </div>
        </td>
      </tr>

      <Modal
        open={confirmOpen}
        title="Unregister SL group?"
        onClose={() => setConfirmOpen(false)}
        footer={
          <>
            <Button
              variant="secondary"
              onClick={() => setConfirmOpen(false)}
              disabled={unregister.isPending}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={handleUnregister}
              loading={unregister.isPending}
              data-testid="confirm-unregister-button"
            >
              Unregister
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-3">
          <p className="text-sm text-fg">
            Unregister{" "}
            <strong className="text-fg">
              {row.slGroupName ?? truncateUuid(row.slGroupUuid)}
            </strong>{" "}
            from this realty group?
          </p>
          <p className="text-sm text-fg-muted">
            Active listings that depend on this SL group must be cancelled or
            closed first. Existing terminal-state auctions are unaffected.
          </p>
          {unregisterError && (
            <p
              className="text-sm text-danger"
              data-testid="unregister-error"
            >
              {unregisterError}
            </p>
          )}
        </div>
      </Modal>
    </>
  );
}
