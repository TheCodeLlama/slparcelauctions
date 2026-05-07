"use client";
import { useRouter, useSearchParams } from "next/navigation";
import { useState, useEffect } from "react";
import { useAdminLedgerList } from "@/hooks/admin/useAdminLedger";
import { AdminLedgerTable } from "./AdminLedgerTable";
import { AdminLedgerFilterBar } from "./AdminLedgerFilterBar";
import { Pagination } from "@/components/ui/Pagination";
import type {
  AdminLedgerFilters,
  AdminLedgerKind,
  AdminLedgerSort,
  AdminLedgerSortColumn,
} from "@/lib/admin/types";

const DEFAULT_PAGE_SIZE = 50;
const ALL_KINDS: AdminLedgerKind[] = [
  "USER_LEDGER", "ESCROW_TXN", "TERMINAL_CMD", "WITHDRAWAL", "BID_RESERVATION",
];
const SORT_COLS: AdminLedgerSortColumn[] = ["createdAt", "amountLindens"];

const DEFAULT_SORT: AdminLedgerSort = { column: "createdAt", direction: "desc" };

function isKind(s: string): s is AdminLedgerKind {
  return (ALL_KINDS as string[]).includes(s);
}

function isSortColumn(s: string): s is AdminLedgerSortColumn {
  return (SORT_COLS as string[]).includes(s);
}

function SkeletonRows() {
  return (
    <div className="space-y-2 py-4" aria-busy="true">
      {Array.from({ length: 5 }).map((_, i) => (
        <div key={i} className="h-12 rounded-lg bg-bg-muted animate-pulse" />
      ))}
    </div>
  );
}

export function AdminLedgerPage() {
  const router = useRouter();
  const searchParams = useSearchParams();

  // Read URL state
  const urlSearch = searchParams?.get("search") ?? "";
  const urlPage = Math.max(0, parseInt(searchParams?.get("page") ?? "0", 10) || 0);
  const urlSizeParsed = parseInt(searchParams?.get("size") ?? String(DEFAULT_PAGE_SIZE), 10);
  const urlSize = Number.isFinite(urlSizeParsed) && urlSizeParsed > 0 ? urlSizeParsed : DEFAULT_PAGE_SIZE;
  const urlKinds = (searchParams?.getAll("kinds") ?? []).filter(isKind) as AdminLedgerKind[];
  const urlUserPublicId = searchParams?.get("userPublicId") ?? "";
  const urlEntryType = searchParams?.get("entryType") ?? "";
  const urlRefType = searchParams?.get("refType") ?? "";
  const urlRefIdRaw = searchParams?.get("refId");
  const urlRefId = urlRefIdRaw ? Number(urlRefIdRaw) : null;
  const urlDateFrom = searchParams?.get("dateFrom") ?? "";
  const urlDateTo = searchParams?.get("dateTo") ?? "";
  const urlAmountMinRaw = searchParams?.get("amountMin");
  const urlAmountMin = urlAmountMinRaw ? Number(urlAmountMinRaw) : null;
  const urlAmountMaxRaw = searchParams?.get("amountMax");
  const urlAmountMax = urlAmountMaxRaw ? Number(urlAmountMaxRaw) : null;

  const urlSort: AdminLedgerSort = (() => {
    const raw = searchParams?.get("sort") ?? "";
    const [col, dir] = raw.split(",");
    if (col && isSortColumn(col)) {
      return { column: col, direction: dir === "asc" ? "asc" : "desc" };
    }
    return DEFAULT_SORT;
  })();

  // Local state for the typeahead label (we only persist publicId in the URL)
  const [selectedUserLabel, setSelectedUserLabel] = useState<string | null>(null);
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- the URL param is the external source of truth; we mirror "user cleared from URL" into local state.
    if (!urlUserPublicId) setSelectedUserLabel(null);
  }, [urlUserPublicId]);

  const [sizeInput, setSizeInput] = useState<string>(String(urlSize));
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- mirror URL into local input on external change.
    setSizeInput(String(urlSize));
  }, [urlSize]);

  const filters: AdminLedgerFilters = {
    search: urlSearch || undefined,
    kinds: urlKinds.length > 0 ? urlKinds : undefined,
    userPublicId: urlUserPublicId || undefined,
    entryType: urlEntryType || undefined,
    refType: urlRefType || undefined,
    refId: urlRefId ?? undefined,
    dateFrom: urlDateFrom || undefined,
    dateTo: urlDateTo || undefined,
    amountMin: urlAmountMin ?? undefined,
    amountMax: urlAmountMax ?? undefined,
    page: urlPage,
    size: urlSize,
    sort: urlSort,
  };

  const { data, isLoading, isError } = useAdminLedgerList(filters);

  function navigate(updates: Partial<{
    search: string | null;
    kinds: AdminLedgerKind[];
    userPublicId: string | null;
    entryType: string | null;
    refType: string | null;
    refId: number | null;
    dateFrom: string | null;
    dateTo: string | null;
    amountMin: number | null;
    amountMax: number | null;
    page: number;
    size: number;
    sort: AdminLedgerSort;
  }>) {
    const params = new URLSearchParams();

    const nextSearch = "search" in updates ? updates.search : urlSearch;
    if (nextSearch) params.set("search", nextSearch);

    const nextKinds = updates.kinds ?? urlKinds;
    for (const k of nextKinds) params.append("kinds", k);

    const nextUserPid = "userPublicId" in updates ? updates.userPublicId : urlUserPublicId;
    if (nextUserPid) params.set("userPublicId", nextUserPid);

    const nextEntryType = "entryType" in updates ? updates.entryType : urlEntryType;
    if (nextEntryType) params.set("entryType", nextEntryType);

    const nextRefType = "refType" in updates ? updates.refType : urlRefType;
    if (nextRefType) params.set("refType", nextRefType);
    const nextRefId = "refId" in updates ? updates.refId : urlRefId;
    if (nextRefId != null && nextRefType) params.set("refId", String(nextRefId));

    const nextDateFrom = "dateFrom" in updates ? updates.dateFrom : urlDateFrom;
    if (nextDateFrom) params.set("dateFrom", nextDateFrom);
    const nextDateTo = "dateTo" in updates ? updates.dateTo : urlDateTo;
    if (nextDateTo) params.set("dateTo", nextDateTo);

    const nextAmountMin = "amountMin" in updates ? updates.amountMin : urlAmountMin;
    if (nextAmountMin != null) params.set("amountMin", String(nextAmountMin));
    const nextAmountMax = "amountMax" in updates ? updates.amountMax : urlAmountMax;
    if (nextAmountMax != null) params.set("amountMax", String(nextAmountMax));

    const nextPage = updates.page ?? 0;
    if (nextPage > 0) params.set("page", String(nextPage));

    const nextSize = updates.size ?? urlSize;
    if (nextSize !== DEFAULT_PAGE_SIZE) params.set("size", String(nextSize));

    const nextSort = updates.sort ?? urlSort;
    if (nextSort.column !== DEFAULT_SORT.column || nextSort.direction !== DEFAULT_SORT.direction) {
      params.set("sort", `${nextSort.column},${nextSort.direction}`);
    }

    const qs = params.toString();
    router.replace(qs ? `/admin/ledger?${qs}` : "/admin/ledger");
  }

  function handleSizeBlur() {
    const parsed = parseInt(sizeInput, 10);
    if (Number.isFinite(parsed) && parsed > 0) {
      navigate({ size: parsed, page: 0 });
    } else {
      setSizeInput(String(urlSize));
    }
  }

  const isDirty =
    !!urlSearch || urlKinds.length > 0 || !!urlUserPublicId || !!urlEntryType ||
    !!urlRefType || !!urlDateFrom || !!urlDateTo ||
    urlAmountMin != null || urlAmountMax != null ||
    urlSort.column !== DEFAULT_SORT.column || urlSort.direction !== DEFAULT_SORT.direction;

  return (
    <div>
      <h1 className="text-2xl font-semibold mb-1">Ledger</h1>
      <p className="text-sm text-fg-muted mb-4">
        Every L$ event across every user, drawn from the user ledger, escrow ledger,
        terminal command queue, admin withdrawals, and bid reservations.
      </p>

      <div className="mb-4">
        <AdminLedgerFilterBar
          search={urlSearch}
          onSearchChange={(s) => navigate({ search: s || null, page: 0 })}
          kinds={urlKinds}
          onKindsChange={(k) => navigate({ kinds: k, page: 0 })}
          selectedUser={
            urlUserPublicId
              ? { publicId: urlUserPublicId, label: selectedUserLabel ?? `User ${urlUserPublicId.slice(0, 8)}…` }
              : null
          }
          onUserChange={(sel) => {
            if (sel) {
              setSelectedUserLabel(sel.label);
              navigate({ userPublicId: sel.publicId, page: 0 });
            } else {
              setSelectedUserLabel(null);
              navigate({ userPublicId: null, page: 0 });
            }
          }}
          entryType={urlEntryType || null}
          onEntryTypeChange={(s) => navigate({ entryType: s, page: 0 })}
          refType={urlRefType || null}
          onRefTypeChange={(s) => navigate({ refType: s, refId: null, page: 0 })}
          refId={urlRefId}
          onRefIdChange={(n) => navigate({ refId: n, page: 0 })}
          dateFrom={urlDateFrom}
          dateTo={urlDateTo}
          onDateFromChange={(s) => navigate({ dateFrom: s || null, page: 0 })}
          onDateToChange={(s) => navigate({ dateTo: s || null, page: 0 })}
          amountMin={urlAmountMin}
          amountMax={urlAmountMax}
          onAmountMinChange={(n) => navigate({ amountMin: n, page: 0 })}
          onAmountMaxChange={(n) => navigate({ amountMax: n, page: 0 })}
          onReset={() =>
            navigate({
              search: null, kinds: [], userPublicId: null, entryType: null,
              refType: null, refId: null, dateFrom: null, dateTo: null,
              amountMin: null, amountMax: null, sort: DEFAULT_SORT, page: 0,
            })
          }
          isDirty={isDirty}
        />
      </div>

      {isLoading && <SkeletonRows />}

      {isError && (
        <div className="text-sm text-danger py-6" data-testid="ledger-error">
          Could not load ledger. Refresh to retry.
        </div>
      )}

      {data && (
        <>
          <div className="text-[11px] text-fg-muted mb-2">
            {data.totalElements === 0
              ? "No events."
              : `Showing ${urlPage * urlSize + 1}–${Math.min((urlPage + 1) * urlSize, data.totalElements)} of ${data.totalElements.toLocaleString()} events`}
          </div>

          <AdminLedgerTable
            rows={data.content}
            sort={urlSort}
            onSortChange={(s) => navigate({ sort: s, page: 0 })}
          />

          <div className="mt-4 flex items-center justify-between gap-4 flex-wrap">
            <div className="flex-1">
              {data.totalPages > 1 && (
                <Pagination
                  page={data.number}
                  totalPages={data.totalPages}
                  onPageChange={(p) => navigate({ page: p })}
                />
              )}
            </div>
            <label className="flex items-center gap-2 text-[11px] text-fg-muted">
              Per page:
              <input
                type="text"
                inputMode="numeric"
                value={sizeInput}
                onChange={(e) => setSizeInput(e.target.value)}
                onBlur={handleSizeBlur}
                onKeyDown={(e) => { if (e.key === "Enter") (e.target as HTMLInputElement).blur(); }}
                data-testid="ledger-page-size-input"
                className="w-16 rounded bg-bg-muted px-2 py-1 text-fg ring-1 ring-border-subtle text-center"
              />
            </label>
          </div>
        </>
      )}
    </div>
  );
}
