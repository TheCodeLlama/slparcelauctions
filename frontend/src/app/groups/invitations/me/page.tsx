"use client";

import Link from "next/link";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { MyInvitationsList } from "@/components/realty/MyInvitationsList";
import { useMyGroupInvitations } from "@/hooks/realty/useMyGroupInvitations";

/**
 * Authenticated recipient view: invitations sent TO the caller. Replaces
 * /dashboard/invitations under the Groups namespace migration (spec
 * section 3.2 + section 5.8 — the bell-icon notification link target).
 */
export default function MyInvitationsPage() {
  const { data, isPending, isError } = useMyGroupInvitations();

  if (isPending) {
    return (
      <div
        className="mx-auto max-w-3xl px-4 py-8"
        data-testid="invitations-recipient-page"
      >
        <LoadingSpinner label="Loading invitations..." />
      </div>
    );
  }

  return (
    <div
      className="mx-auto max-w-3xl px-4 py-8 flex flex-col gap-6"
      data-testid="invitations-recipient-page"
    >
      <header className="flex items-center justify-between gap-3">
        <h1 className="text-xl font-bold tracking-tight font-display">
          Group invitations
        </h1>
        <Link
          href="/groups/me"
          className="text-xs text-fg-muted hover:underline"
        >
          Back to my groups
        </Link>
      </header>
      {isError ? (
        <p className="text-sm text-danger">
          Couldn&apos;t load invitations. Try again.
        </p>
      ) : (
        <MyInvitationsList invitations={data ?? []} />
      )}
    </div>
  );
}
