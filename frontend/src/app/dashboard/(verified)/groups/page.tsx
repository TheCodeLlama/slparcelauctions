"use client";

/* eslint-disable @next/next/no-img-element -- logo bytes are API-served binary content */
import Link from "next/link";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { EmptyState } from "@/components/ui/EmptyState";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { Users2 } from "@/components/ui/icons";
import {
  useAcceptInvitation,
  useDeclineInvitation,
  useMyInvitations,
  useMyRealtyGroups,
} from "@/hooks/realty/useRealtyGroups";
import { apiUrl } from "@/lib/api/url";
import type { InvitationDto, RealtyGroupSummaryDto } from "@/types/realty";

/**
 * Caller-facing landing page for the realty-groups feature.
 *
 * Layout:
 * - Header with "Create group" CTA.
 * - Embedded "Pending invitations" strip (horizontally scrolling) at the
 *   top — appears only if the caller has any pending invitations.
 * - List of summary cards for groups the caller leads or is an agent in.
 * - Empty state when neither list has any items.
 *
 * This page is a client component because it relies on TanStack Query
 * + the auth cookie; SSR would fail on the Amplify runtime which has no
 * client JWT (see {@code frontend/AGENTS.md} SSR caveats).
 */
export default function GroupsListPage() {
  const myGroups = useMyRealtyGroups();
  const myInvitations = useMyInvitations();

  const hasInvitations =
    myInvitations.data && myInvitations.data.length > 0;
  const hasGroups = myGroups.data && myGroups.data.length > 0;
  const showEmptyState =
    myGroups.isSuccess &&
    myInvitations.isSuccess &&
    !hasInvitations &&
    !hasGroups;

  return (
    <div className="mx-auto max-w-4xl px-4 py-8 flex flex-col gap-8">
      <header className="flex items-center justify-between gap-3">
        <h1 className="text-xl font-bold tracking-tight font-display">
          My realty groups
        </h1>
        <Link href="/dashboard/groups/create">
          <Button variant="primary" data-testid="groups-create-cta">
            Create group
          </Button>
        </Link>
      </header>

      {hasInvitations && (
        <section aria-label="Pending invitations" className="flex flex-col gap-3">
          <h2 className="text-sm font-semibold tracking-tight">
            Pending invitations
          </h2>
          <div
            className="flex flex-row gap-3 overflow-x-auto pb-2"
            data-testid="pending-invitations-strip"
          >
            {myInvitations.data!.map((inv) => (
              <PendingInvitationCard key={inv.publicId} inv={inv} />
            ))}
          </div>
          <Link
            href="/dashboard/invitations"
            className="text-xs text-fg-muted underline-offset-2 hover:underline"
          >
            See all invitations
          </Link>
        </section>
      )}

      <section aria-label="My groups" className="flex flex-col gap-3">
        {myGroups.isPending && (
          <p className="text-sm text-fg-muted">Loading your groups...</p>
        )}
        {myGroups.isError && (
          <p className="text-sm text-danger">
            Couldn&apos;t load your groups. Try again.
          </p>
        )}
        {hasGroups && (
          <ul
            className="flex flex-col gap-2"
            data-testid="my-groups-list"
          >
            {myGroups.data!.map((g) => (
              <MyGroupRow key={g.publicId} group={g} />
            ))}
          </ul>
        )}
      </section>

      {showEmptyState && (
        <EmptyState
          icon={Users2}
          headline="You're not part of any realty groups yet"
          description="Create one to organize your listings or wait for a leader to invite you."
        >
          <Link href="/dashboard/groups/create">
            <Button variant="primary" data-testid="groups-empty-create-cta">
              Create group
            </Button>
          </Link>
        </EmptyState>
      )}
    </div>
  );
}

function PendingInvitationCard({ inv }: { inv: InvitationDto }) {
  const accept = useAcceptInvitation();
  const decline = useDeclineInvitation();
  const busy = accept.isPending || decline.isPending;
  return (
    <Card
      className="min-w-[18rem] shrink-0 p-4"
      data-testid={`invitation-card-${inv.publicId}`}
    >
      <div className="flex flex-col gap-2">
        <span className="text-sm font-semibold text-fg">{inv.groupName}</span>
        <span className="text-xs text-fg-muted">
          Invited by {inv.invitedByDisplayName}
        </span>
        <StatusBadge tone="warning" className="self-start">
          PENDING
        </StatusBadge>
        <div className="flex justify-end gap-2 mt-2">
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
    </Card>
  );
}

function MyGroupRow({ group }: { group: RealtyGroupSummaryDto }) {
  const logo = apiUrl(group.logoUrl);
  return (
    <li>
      <Link
        href={`/dashboard/groups/${encodeURIComponent(group.slug)}/manage`}
        className="flex items-center gap-3 rounded-lg border border-border bg-surface-raised px-3 py-2.5 transition-colors hover:bg-bg-hover"
        data-testid={`my-group-row-${group.publicId}`}
      >
        {logo ? (
          <img
            src={logo}
            alt=""
            className="h-10 w-10 rounded object-cover"
            aria-hidden="true"
          />
        ) : (
          <div className="h-10 w-10 rounded bg-info-bg" aria-hidden="true" />
        )}
        <div className="flex flex-col gap-0.5 min-w-0 flex-1">
          <span className="text-sm font-semibold text-fg truncate">
            {group.name}
          </span>
          <span className="text-xs text-fg-muted">
            {group.memberCount} member{group.memberCount === 1 ? "" : "s"}
          </span>
        </div>
      </Link>
    </li>
  );
}
