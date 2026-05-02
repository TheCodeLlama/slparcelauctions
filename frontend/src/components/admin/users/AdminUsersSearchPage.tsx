"use client";
import { useRouter, useSearchParams } from "next/navigation";
import { useAdminUsersList } from "@/hooks/admin/useAdminUsersList";
import { AdminUsersTable } from "./AdminUsersTable";
import { Pagination } from "@/components/ui/Pagination";

const PAGE_SIZE = 25;

function SkeletonRows() {
  return (
    <div className="space-y-2 py-4" aria-busy="true">
      {Array.from({ length: 5 }).map((_, i) => (
        <div key={i} className="h-12 rounded-lg bg-bg-muted animate-pulse" />
      ))}
    </div>
  );
}

export function AdminUsersSearchPage() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const search = searchParams?.get("search") ?? "";
  const page = Math.max(0, parseInt(searchParams?.get("page") ?? "0", 10) || 0);

  const filters = { search: search || undefined, page, size: PAGE_SIZE };
  const { data, isLoading, isError } = useAdminUsersList(filters);

  function navigate(overrides: { search?: string; page?: number }) {
    const params = new URLSearchParams();
    const nextSearch = "search" in overrides ? overrides.search : search;
    const nextPage = overrides.page ?? 0;
    if (nextSearch) params.set("search", nextSearch);
    if (nextPage > 0) params.set("page", String(nextPage));
    const qs = params.toString();
    router.push(qs ? `/admin/users?${qs}` : "/admin/users");
  }

  return (
    <div>
      <h1 className="text-2xl font-semibold mb-4">Users</h1>

      <input
        key={search}
        defaultValue={search}
        placeholder="Search by email, display name, SL display name, or paste a UUID"
        data-testid="user-search-input"
        onKeyDown={(e) => {
          if (e.key === "Enter") {
            const v = (e.target as HTMLInputElement).value.trim();
            navigate({ search: v || undefined, page: 0 });
          }
        }}
        className="w-full mb-4 rounded-lg bg-bg-muted px-4 py-2.5 text-sm text-fg placeholder:text-fg-muted ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand"
      />

      {isLoading && <SkeletonRows />}

      {isError && (
        <div className="text-sm text-danger-flat py-6">Could not load users. Refresh to retry.</div>
      )}

      {data && (
        <>
          <AdminUsersTable rows={data.content} />
          {data.totalPages > 1 && (
            <div className="mt-4">
              <Pagination
                page={data.number}
                totalPages={data.totalPages}
                onPageChange={(p) => navigate({ page: p })}
              />
            </div>
          )}
        </>
      )}
    </div>
  );
}
