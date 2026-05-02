"use client";
import { useState, useEffect } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useAdminReportsByListing } from "@/hooks/admin/useAdminReportsByListing";
import { useWarnSeller } from "@/hooks/admin/useWarnSeller";
import { useSuspendListingFromReport } from "@/hooks/admin/useSuspendListingFromReport";
import { useCancelListingFromReport } from "@/hooks/admin/useCancelListingFromReport";
import { Button } from "@/components/ui/Button";
import { AdminReportCard } from "./AdminReportCard";
import type { AdminReportListingRow } from "@/lib/admin/types";

const NOTES_MAX = 1000;

type Props = {
  auctionId: number | null;
  listingRow: AdminReportListingRow | null;
  hasPrev: boolean;
  hasNext: boolean;
  onPrev: () => void;
  onNext: () => void;
  onClose: () => void;
};

export function AdminReportSlideOver({
  auctionId,
  listingRow,
  hasPrev,
  hasNext,
  onPrev,
  onNext,
  onClose,
}: Props) {
  const router = useRouter();
  const [notes, setNotes] = useState("");

  const { data: reports, isLoading } = useAdminReportsByListing(auctionId);
  const warnSeller = useWarnSeller();
  const suspendListing = useSuspendListingFromReport();
  const cancelListing = useCancelListingFromReport();

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setNotes("");
  }, [auctionId]);

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  if (!auctionId) return null;

  const isPending =
    warnSeller.isPending ||
    suspendListing.isPending ||
    cancelListing.isPending;
  const notesEmpty = notes.trim().length === 0;
  const canAct = !notesEmpty && !isPending;

  const openCount = reports?.filter((r) => r.status === "OPEN").length ?? 0;

  const handleWarn = () => {
    if (!canAct) return;
    warnSeller.mutate(
      { auctionId, notes },
      { onSuccess: () => { onClose(); } }
    );
  };

  const handleSuspend = () => {
    if (!canAct) return;
    suspendListing.mutate(
      { auctionId, notes },
      { onSuccess: () => { onClose(); } }
    );
  };

  const handleCancel = () => {
    if (!canAct) return;
    cancelListing.mutate(
      { auctionId, notes },
      { onSuccess: () => { onClose(); } }
    );
  };

  const handleBanSeller = () => {
    if (!listingRow) return;
    router.push(`/admin/users/${listingRow.sellerUserId}`);
  };

  return (
    <>
      <div
        className="fixed inset-0 z-30 bg-inverse-surface/20"
        onClick={onClose}
        aria-hidden="true"
      />
      <aside
        className="fixed top-16 right-0 bottom-0 z-40 w-[560px] flex flex-col bg-bg-subtle border-l border-border-subtle shadow-md overflow-hidden"
        data-testid="admin-report-slideover"
        role="dialog"
        aria-modal="true"
        aria-label="Listing reports detail"
      >
        {/* Navigation bar */}
        <div className="flex items-center justify-between px-5 py-3 border-b border-border-subtle shrink-0">
          <div className="flex items-center gap-1">
            <button
              type="button"
              onClick={onPrev}
              disabled={!hasPrev}
              aria-label="Previous listing"
              className="p-1.5 rounded-lg text-fg-muted hover:bg-bg-muted disabled:opacity-30 disabled:pointer-events-none"
            >
              ←
            </button>
            <button
              type="button"
              onClick={onNext}
              disabled={!hasNext}
              aria-label="Next listing"
              className="p-1.5 rounded-lg text-fg-muted hover:bg-bg-muted disabled:opacity-30 disabled:pointer-events-none"
            >
              →
            </button>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="p-1.5 rounded-lg text-fg-muted hover:bg-bg-muted"
          >
            ✕
          </button>
        </div>

        <div className="flex-1 overflow-y-auto px-5 py-4 flex flex-col gap-4">
          {isLoading && (
            <div className="text-sm text-fg-muted">Loading…</div>
          )}

          {listingRow && (
            <>
              {/* Header */}
              <div className="flex items-start justify-between gap-2 flex-wrap">
                <div className="flex flex-col gap-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="text-[11px] font-medium bg-danger-bg text-danger-flat px-2 py-0.5 rounded-full">
                      {openCount} open report{openCount !== 1 ? "s" : ""}
                    </span>
                    <span
                      className="text-[11px] font-medium px-2 py-0.5 rounded-full bg-bg-muted text-fg-muted"
                      data-testid="auction-status-chip"
                    >
                      {listingRow.auctionStatus}
                    </span>
                  </div>
                  <div className="text-sm font-semibold text-fg">
                    {listingRow.auctionTitle}
                  </div>
                  <div className="text-sm text-fg-muted">
                    Auction #{listingRow.auctionId}
                    {listingRow.sellerDisplayName && (
                      <span>
                        {" "}
                        · Seller:{" "}
                        <Link
                          href={`/admin/users/${listingRow.sellerUserId}`}
                          className="text-brand underline underline-offset-2"
                        >
                          {listingRow.sellerDisplayName}
                        </Link>
                      </span>
                    )}
                  </div>
                </div>
              </div>

              {/* Actions row */}
              <div className="flex flex-wrap gap-2">
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={handleWarn}
                  disabled={!canAct}
                  loading={warnSeller.isPending}
                  data-testid="warn-seller-btn"
                >
                  Warn seller
                </Button>
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={handleSuspend}
                  disabled={!canAct}
                  loading={suspendListing.isPending}
                  data-testid="suspend-listing-btn"
                >
                  Suspend listing
                </Button>
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={handleCancel}
                  disabled={!canAct}
                  loading={cancelListing.isPending}
                  data-testid="cancel-listing-btn"
                >
                  Cancel listing
                </Button>
                <Button
                  variant="destructive"
                  size="sm"
                  onClick={handleBanSeller}
                  data-testid="ban-seller-btn"
                >
                  Ban seller →
                </Button>
              </div>

              {/* Reports list */}
              {reports && reports.length > 0 && (
                <div className="flex flex-col gap-3">
                  <div className="text-xs font-medium text-fg">
                    Reports ({reports.length})
                  </div>
                  {reports.map((report) => (
                    <AdminReportCard key={report.id} report={report} />
                  ))}
                </div>
              )}

              {reports && reports.length === 0 && (
                <div className="text-sm text-fg-muted">
                  No reports found for this listing.
                </div>
              )}

              {/* Shared notes */}
              <div className="flex flex-col gap-1">
                <label className="text-xs font-medium text-fg">
                  Admin notes <span className="text-danger-flat">*</span>
                  <span className="text-fg-muted font-normal ml-1">
                    (required for Warn / Suspend / Cancel)
                  </span>
                </label>
                <textarea
                  rows={4}
                  value={notes}
                  disabled={isPending}
                  onChange={(e) => setNotes(e.target.value.slice(0, NOTES_MAX))}
                  placeholder="Notes for this action…"
                  data-testid="admin-report-notes"
                  className="w-full resize-y rounded-lg bg-bg-muted px-4 py-3 text-fg placeholder:text-fg-muted ring-1 ring-border-subtle transition-all focus:outline-none focus:ring-2 focus:ring-brand disabled:opacity-50"
                />
                <div className="self-end text-[11px] font-medium text-fg-muted">
                  {notes.length} / {NOTES_MAX}
                </div>
              </div>
            </>
          )}
        </div>
      </aside>
    </>
  );
}
