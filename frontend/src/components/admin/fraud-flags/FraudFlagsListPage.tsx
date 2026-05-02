"use client";
import { useRouter, useSearchParams } from "next/navigation";
import { useAdminFraudFlagsList } from "@/hooks/admin/useAdminFraudFlagsList";
import { FraudFlagFilters } from "./FraudFlagFilters";
import { FraudFlagTable } from "./FraudFlagTable";
import { FraudFlagSlideOver } from "./FraudFlagSlideOver";
import { Pagination } from "@/components/ui/Pagination";
import type { FraudFlagListStatus } from "@/lib/admin/types";

const PAGE_SIZE = 25;

function parseFlagId(raw: string | null): number | null {
  if (!raw) return null;
  const n = parseInt(raw, 10);
  return Number.isFinite(n) && n > 0 ? n : null;
}

function parseStatus(raw: string | null): FraudFlagListStatus {
  if (raw === "resolved" || raw === "all") return raw;
  return "open";
}

export function FraudFlagsListPage() {
  const router = useRouter();
  const searchParams = useSearchParams();

  const status = parseStatus(searchParams?.get("status") ?? null);
  const page = Math.max(0, parseInt(searchParams?.get("page") ?? "0", 10) || 0);
  const flagId = parseFlagId(searchParams?.get("flagId") ?? null);

  const { data: list, isLoading, isError } = useAdminFraudFlagsList({
    status,
    reasons: [],
    page,
    size: PAGE_SIZE,
  });

  const ids = list?.content.map((r) => r.id) ?? [];
  const idx = flagId !== null ? ids.indexOf(flagId) : -1;

  function buildUrl(overrides: {
    status?: FraudFlagListStatus;
    page?: number;
    flagId?: number | null;
  }): string {
    const params = new URLSearchParams();
    const nextStatus = overrides.status ?? status;
    const nextPage = overrides.page ?? page;
    const nextFlagId = "flagId" in overrides ? overrides.flagId : flagId;
    if (nextStatus !== "open") params.set("status", nextStatus);
    if (nextPage > 0) params.set("page", String(nextPage));
    if (nextFlagId !== null && nextFlagId !== undefined) params.set("flagId", String(nextFlagId));
    const qs = params.toString();
    return qs ? `/admin/fraud-flags?${qs}` : "/admin/fraud-flags";
  }

  const handleStatusChange = (s: FraudFlagListStatus) => {
    router.replace(buildUrl({ status: s, page: 0, flagId: null }), { scroll: false });
  };

  const handleSelect = (id: number) => {
    router.replace(buildUrl({ flagId: id }), { scroll: false });
  };

  const handleClose = () => {
    router.replace(buildUrl({ flagId: null }), { scroll: false });
  };

  const handlePrev = () => {
    if (idx > 0) router.replace(buildUrl({ flagId: ids[idx - 1] }), { scroll: false });
  };

  const handleNext = () => {
    if (idx < ids.length - 1) router.replace(buildUrl({ flagId: ids[idx + 1] }), { scroll: false });
  };

  const handlePage = (p: number) => {
    router.replace(buildUrl({ page: p, flagId: null }), { scroll: false });
  };

  return (
    <div>
      <div className="flex items-center justify-between mb-4">
        <h1 className="text-2xl font-semibold">Fraud Flags</h1>
      </div>

      <div className="mb-4">
        <FraudFlagFilters status={status} onStatusChange={handleStatusChange} />
      </div>

      {isLoading && (
        <div className="text-sm text-fg-muted py-8">Loading…</div>
      )}

      {isError && (
        <div className="text-sm text-danger py-8">
          Could not load fraud flags. Refresh to retry.
        </div>
      )}

      {list && (
        <>
          <FraudFlagTable
            rows={list.content}
            selectedId={flagId}
            onSelect={handleSelect}
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

      <FraudFlagSlideOver
        flagId={flagId}
        hasPrev={idx > 0}
        hasNext={idx !== -1 && idx < ids.length - 1}
        onPrev={handlePrev}
        onNext={handleNext}
        onClose={handleClose}
      />
    </div>
  );
}
