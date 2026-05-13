"use client";

import Link from "next/link";
import { Button } from "@/components/ui/Button";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { MyGroupsList } from "@/components/realty/MyGroupsList";
import { useMyRealtyGroups } from "@/hooks/realty/useRealtyGroups";
import { useCurrentUser } from "@/lib/user";

/**
 * Authenticated personal view: every realty group the caller belongs to.
 * Replaces /dashboard/groups under the Groups namespace migration (spec
 * section 3.2). Client component because TanStack Query + the auth cookie
 * are both client-only on the Amplify runtime (see frontend/AGENTS.md SSR
 * caveats).
 */
export default function MyGroupsPage() {
  const { data: user, isPending: meLoading } = useCurrentUser();
  const myGroups = useMyRealtyGroups();

  if (meLoading || myGroups.isPending) {
    return (
      <div className="mx-auto max-w-4xl px-4 py-8" data-testid="my-groups-page">
        <LoadingSpinner label="Loading your groups..." />
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-4xl px-4 py-8 flex flex-col gap-6" data-testid="my-groups-page">
      <header className="flex items-center justify-between gap-3">
        <h1 className="text-xl font-bold tracking-tight font-display">
          My groups
        </h1>
        <Link href="/groups/new">
          <Button variant="primary" data-testid="groups-create-cta">
            Create group
          </Button>
        </Link>
      </header>
      {myGroups.isError ? (
        <p className="text-sm text-danger">
          Couldn&apos;t load your groups. Try again.
        </p>
      ) : (
        <MyGroupsList
          groups={myGroups.data ?? []}
          callerUserPublicId={user?.publicId ?? null}
        />
      )}
    </div>
  );
}
