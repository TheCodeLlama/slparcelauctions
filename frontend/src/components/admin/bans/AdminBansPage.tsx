"use client";
import { useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useAdminBansList } from "@/hooks/admin/useAdminBansList";
import { AdminBansFilters, type BanListStatus, type BanTypeFilter } from "./AdminBansFilters";
import { AdminBansTable } from "./AdminBansTable";
import { CreateBanModal } from "./CreateBanModal";
import { LiftBanModal } from "./LiftBanModal";
import { Button } from "@/components/ui/Button";
import { Pagination } from "@/components/ui/Pagination";
import type { AdminBanRow, BanType } from "@/lib/admin/types";

const PAGE_SIZE = 25;

function parseStatus(raw: string | null): BanListStatus {
  if (raw === "history") return "history";
  return "active";
}

function parseTypeFilter(raw: string | null): BanTypeFilter {
  if (raw === "IP" || raw === "AVATAR" || raw === "BOTH") return raw;
  return "ALL";
}

export function AdminBansPage() {
  const router = useRouter();
  const searchParams = useSearchParams();

  const status = parseStatus(searchParams?.get("tab") ?? null);
  const typeFilter = parseTypeFilter(searchParams?.get("type") ?? null);
  const page = Math.max(0, parseInt(searchParams?.get("page") ?? "0", 10) || 0);

  const [createModalOpen, setCreateModalOpen] = useState(false);
  const [liftTarget, setLiftTarget] = useState<AdminBanRow | null>(null);

  const { data: list, isLoading, isError } = useAdminBansList({
    status,
    page,
    size: PAGE_SIZE,
  });

  const filteredRows =
    list?.content.filter((row) => typeFilter === "ALL" || row.banType === (typeFilter as BanType)) ??
    [];

  function buildUrl(overrides: {
    tab?: BanListStatus;
    type?: BanTypeFilter;
    page?: number;
  }): string {
    const params = new URLSearchParams();
    const nextTab = overrides.tab ?? status;
    const nextType = overrides.type ?? typeFilter;
    const nextPage = overrides.page ?? page;
    if (nextTab !== "active") params.set("tab", nextTab);
    if (nextType !== "ALL") params.set("type", nextType);
    if (nextPage > 0) params.set("page", String(nextPage));
    const qs = params.toString();
    return qs ? `/admin/bans?${qs}` : "/admin/bans";
  }

  const handleStatusChange = (s: BanListStatus) => {
    router.replace(buildUrl({ tab: s, type: "ALL", page: 0 }), { scroll: false });
  };

  const handleTypeChange = (t: BanTypeFilter) => {
    router.replace(buildUrl({ type: t, page: 0 }), { scroll: false });
  };

  const handlePage = (p: number) => {
    router.replace(buildUrl({ page: p }), { scroll: false });
  };

  return (
    <div>
      <div className="flex items-center justify-between mb-4">
        <h1 className="text-2xl font-semibold">Bans</h1>
        <Button
          variant="primary"
          size="sm"
          onClick={() => setCreateModalOpen(true)}
          data-testid="create-ban-btn"
        >
          + Create ban
        </Button>
      </div>

      <div className="mb-4">
        <AdminBansFilters
          status={status}
          typeFilter={typeFilter}
          onStatusChange={handleStatusChange}
          onTypeChange={handleTypeChange}
        />
      </div>

      {isLoading && (
        <div className="text-sm text-fg-muted py-8">Loading…</div>
      )}

      {isError && (
        <div className="text-sm text-danger py-8">
          Could not load bans. Refresh to retry.
        </div>
      )}

      {list && (
        <>
          <AdminBansTable
            rows={filteredRows}
            onLift={setLiftTarget}
            showLift={status === "active"}
          />
          {list.totalPages > 1 && (
            <div className="mt-4">
              <Pagination
                page={list.number}
                totalPages={list.totalPages}
                onPageChange={handlePage}
              />
            </div>
          )}
        </>
      )}

      <CreateBanModal
        open={createModalOpen}
        onClose={() => setCreateModalOpen(false)}
      />

      {liftTarget && (
        <LiftBanModal
          ban={liftTarget}
          onClose={() => setLiftTarget(null)}
        />
      )}
    </div>
  );
}
