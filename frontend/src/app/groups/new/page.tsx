"use client";

import Link from "next/link";
import { GroupCreateForm } from "@/components/realty/GroupCreateForm";

/**
 * Authenticated create-group form. Replaces /dashboard/groups/create under
 * the Groups namespace migration (spec section 3.2). The form's post-create
 * redirect updates separately in Task 29 (sweep) — when this page lands,
 * the form still pushes to the old /dashboard/groups/[slug]/manage; Task 29
 * retargets to /groups/[slug]/profile.
 */
export default function CreateGroupPage() {
  return (
    <div
      className="mx-auto max-w-2xl px-4 py-8 flex flex-col gap-6"
      data-testid="group-create-form"
    >
      <header className="flex items-center justify-between gap-3">
        <h1 className="text-xl font-bold tracking-tight font-display">
          Create a realty group
        </h1>
        <Link
          href="/groups/me"
          className="text-xs text-fg-muted hover:underline"
        >
          Back to my groups
        </Link>
      </header>
      <GroupCreateForm />
    </div>
  );
}
