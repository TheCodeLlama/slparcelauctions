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
  useMyInvitations,
} from "@/hooks/realty/useRealtyGroups";
import { permissionLabel } from "@/lib/realty/permissions";
import type { InvitationDto } from "@/types/realty";

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
 * Dedicated invitations page — separate from the strip on
 * `/dashboard/groups`. Lists every pending invitation addressed to the
 * caller, with full preview of who invited them, the permission set
 * they would receive, and expiry.
 */
export default function InvitationsPage() {
  const myInvitations = useMyInvitations();

  return (
    <div className="mx-auto max-w-3xl px-4 py-8 flex flex-col gap-6">
      <header className="flex items-center justify-between gap-3">
        <h1 className="text-xl font-bold tracking-tight font-display">
          Invitations
        </h1>
        <Link
          href="/dashboard/groups"
          className="text-xs text-fg-muted hover:underline"
        >
          Back to my groups
        </Link>
      </header>

      {myInvitations.isPending && (
        <p className="text-sm text-fg-muted">Loading invitations...</p>
      )}
      {myInvitations.isError && (
        <p className="text-sm text-danger">
          Couldn&apos;t load invitations. Try again.
        </p>
      )}
      {myInvitations.isSuccess && myInvitations.data.length === 0 && (
        <EmptyState
          icon={Inbox}
          headline="No pending invitations"
          description="You will see new invitations here when a realty group leader sends you one."
        />
      )}
      {myInvitations.isSuccess && myInvitations.data.length > 0 && (
        <ul className="flex flex-col gap-3" data-testid="invitations-list">
          {myInvitations.data.map((inv) => (
            <InvitationCard key={inv.publicId} inv={inv} />
          ))}
        </ul>
      )}
    </div>
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
              href={`/group/${encodeURIComponent(inv.groupSlug)}`}
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
