"use client";

import Link from "next/link";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { EmptyState } from "@/components/ui/EmptyState";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { Inbox } from "@/components/ui/icons";
import {
  useAcceptInvitation,
  useDeclineInvitation,
} from "@/hooks/realty/useRealtyGroups";
import { permissionLabel } from "@/lib/realty/permissions";
import type { InvitationDto } from "@/types/realty";

export interface MyInvitationsListProps {
  invitations: InvitationDto[];
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
 * Renders the caller's pending realty-group invitations as a vertical list
 * of cards with accept/decline affordances. Extracted from the legacy
 * `/dashboard/invitations` page so the new `/groups/invitations/me` route
 * can be a thin wrapper.
 */
export function MyInvitationsList({ invitations }: MyInvitationsListProps) {
  if (invitations.length === 0) {
    return (
      <EmptyState
        icon={Inbox}
        headline="No pending invitations"
        description="You will see new invitations here when a realty group leader sends you one."
      />
    );
  }
  return (
    <ul className="flex flex-col gap-3" data-testid="invitations-list">
      {invitations.map((inv) => (
        <InvitationCard key={inv.publicId} inv={inv} />
      ))}
    </ul>
  );
}

function InvitationCard({ inv }: { inv: InvitationDto }) {
  const accept = useAcceptInvitation();
  const decline = useDeclineInvitation();
  const busy = accept.isPending || decline.isPending;
  return (
    <Card data-testid={`invitation-card-${inv.publicId}`}>
      <Card.Body>
        <div className="flex flex-col gap-3">
          <div className="flex items-center gap-2 flex-wrap">
            <Link
              href={`/groups/${encodeURIComponent(inv.groupSlug)}`}
              className="text-sm font-semibold text-fg hover:underline"
            >
              {inv.groupName}
            </Link>
            <StatusBadge tone="warning">PENDING</StatusBadge>
          </div>
          <span className="text-xs text-fg-muted">
            Invited by {inv.invitedByDisplayName}
            {" · "}Expires {formatExpiry(inv.expiresAt)}
          </span>
          {inv.permissions.length > 0 && (
            <div className="flex flex-col gap-1">
              <span className="text-xs font-medium text-fg-muted">
                You would receive these permissions:
              </span>
              <ul className="flex flex-wrap gap-1">
                {inv.permissions.map((p) => (
                  <li
                    key={p}
                    className="inline-flex items-center rounded bg-info-bg px-1.5 py-0.5 text-[11px] font-medium text-info"
                  >
                    {permissionLabel(p)}
                  </li>
                ))}
              </ul>
            </div>
          )}
          <div className="flex justify-end gap-2">
            <Button
              size="sm"
              variant="secondary"
              onClick={() => decline.mutate(inv.publicId)}
              disabled={busy}
              data-testid={`invitation-decline-${inv.publicId}`}
            >
              Decline
            </Button>
            <Button
              size="sm"
              variant="primary"
              onClick={() => accept.mutate(inv.publicId)}
              disabled={busy}
              loading={accept.isPending}
              data-testid={`invitation-accept-${inv.publicId}`}
            >
              Accept
            </Button>
          </div>
        </div>
      </Card.Body>
    </Card>
  );
}
