"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { Modal } from "@/components/ui/Modal";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { Mail, UserPlus } from "@/components/ui/icons";
import { EmptyState } from "@/components/ui/EmptyState";
import {
  useRealtyGroupInvitations,
  useRevokeInvitation,
} from "@/hooks/realty/useRealtyGroups";
import { permissionLabel } from "@/lib/realty/permissions";
import type { InvitationDto, InvitationStatus } from "@/types/realty";
import { InviteForm } from "./InviteForm";

export interface InvitationsTabProps {
  groupPublicId: string;
}

function statusTone(
  status: InvitationStatus,
): "default" | "success" | "danger" | "warning" {
  switch (status) {
    case "PENDING":
      return "warning";
    case "ACCEPTED":
      return "success";
    case "EXPIRED":
    case "REVOKED":
    case "DECLINED":
      return "default";
    default:
      return "default";
  }
}

function formatExpiry(expiresAt: string): string {
  const date = new Date(expiresAt);
  if (Number.isNaN(date.getTime())) return expiresAt;
  return date.toLocaleString("en-US", {
    month: "short",
    day: "numeric",
    year: "numeric",
  });
}

/**
 * Invitations tab on the manage page. Lists every invitation (any status)
 * tied to the group and offers an inline "Send invitation" modal.
 *
 * Pending invitations carry a "Revoke" button. Non-pending invitations
 * are shown read-only for audit / context.
 */
export function InvitationsTab({ groupPublicId }: InvitationsTabProps) {
  const { data, isPending, isError } = useRealtyGroupInvitations(groupPublicId);
  const revoke = useRevokeInvitation();
  const [inviteOpen, setInviteOpen] = useState(false);

  return (
    <Card>
      <Card.Header>
        <div className="flex items-center justify-between gap-2">
          <h2 className="text-sm font-semibold tracking-tight">Invitations</h2>
          <Button
            type="button"
            size="sm"
            variant="primary"
            leftIcon={<UserPlus className="size-4" aria-hidden="true" />}
            onClick={() => setInviteOpen(true)}
            data-testid="invitations-send-button"
          >
            Send invitation
          </Button>
        </div>
      </Card.Header>
      <Card.Body>
        {isPending ? (
          <p className="text-sm text-fg-muted">Loading invitations...</p>
        ) : isError ? (
          <p className="text-sm text-danger">
            Couldn&apos;t load invitations. Try again.
          </p>
        ) : !data || data.length === 0 ? (
          <EmptyState
            icon={Mail}
            headline="No invitations yet"
            description="Send an invitation to add an agent to this group."
          />
        ) : (
          <ul className="flex flex-col gap-2" data-testid="invitations-list">
            {data.map((inv) => (
              <InvitationRow
                key={inv.publicId}
                inv={inv}
                groupPublicId={groupPublicId}
                onRevoke={(invitationPublicId) =>
                  revoke.mutate({ publicId: groupPublicId, invitationPublicId })
                }
                revoking={revoke.isPending}
              />
            ))}
          </ul>
        )}
      </Card.Body>

      <Modal
        open={inviteOpen}
        title="Send invitation"
        onClose={() => setInviteOpen(false)}
      >
        <InviteForm
          groupPublicId={groupPublicId}
          onComplete={() => setInviteOpen(false)}
        />
      </Modal>
    </Card>
  );
}

function InvitationRow({
  inv,
  onRevoke,
  revoking,
}: {
  inv: InvitationDto;
  groupPublicId: string;
  onRevoke: (invitationPublicId: string) => void;
  revoking: boolean;
}) {
  return (
    <li
      className="flex items-center gap-3 rounded-lg border border-border bg-surface-raised px-3 py-2.5"
      data-testid={`invitation-row-${inv.publicId}`}
    >
      <div className="flex flex-col gap-0.5 min-w-0 flex-1">
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-sm font-medium text-fg">
            Invitation #{inv.publicId.slice(0, 8)}
          </span>
          <StatusBadge tone={statusTone(inv.status)}>{inv.status}</StatusBadge>
        </div>
        <span className="text-xs text-fg-muted">
          Invited by {inv.invitedByDisplayName}
          {" · "}Expires {formatExpiry(inv.expiresAt)}
        </span>
        {inv.permissions.length > 0 && (
          <ul className="flex flex-wrap gap-1 mt-1">
            {inv.permissions.map((p) => (
              <li
                key={p}
                className="inline-flex items-center rounded bg-info-bg px-1.5 py-0.5 text-[11px] font-medium text-info"
              >
                {permissionLabel(p)}
              </li>
            ))}
          </ul>
        )}
      </div>
      {inv.status === "PENDING" && (
        <Button
          type="button"
          size="sm"
          variant="secondary"
          onClick={() => onRevoke(inv.publicId)}
          disabled={revoking}
          data-testid={`invitation-revoke-${inv.publicId}`}
        >
          Revoke
        </Button>
      )}
    </li>
  );
}
