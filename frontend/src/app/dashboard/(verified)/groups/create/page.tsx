import Link from "next/link";
import { GroupCreateForm } from "@/components/realty/GroupCreateForm";

export default function GroupCreatePage() {
  return (
    <div className="mx-auto max-w-2xl px-4 py-8 flex flex-col gap-6">
      <header className="flex items-center justify-between gap-3">
        <h1 className="text-xl font-bold tracking-tight font-display">
          Create a realty group
        </h1>
        <Link
          href="/dashboard/groups"
          className="text-xs text-fg-muted hover:underline"
        >
          Back to my groups
        </Link>
      </header>
      <GroupCreateForm />
    </div>
  );
}
