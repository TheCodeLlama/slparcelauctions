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
        className="fixed top-16 right-0 bottom-0 z-40 w-[560px] flex flex-col bg-surface-container-low border-l border-outline-variant shadow-elevated overflow-hidden"
        data-testid="admin-report-slideover"
        role="dialog"
        aria-modal="true"
        aria-label="Listing reports detail"
      >
        {/* Navigation bar */}
        <div className="flex items-center justify-between px-5 py-3 border-b border-outline-variant shrink-0">
          <div className="flex items-center gap-1">
            <button
              type="button"
              onClick={onPrev}
              disabled={!hasPrev}
              aria-label="Previous listing"
              className="p-1.5 rounded-default text-on-surface-variant hover:bg-surface-container disabled:opacity-30 disabled:pointer-events-none"
            >
              ←
            </button>
            <button
              type="button"
              onClick={onNext}
              disabled={!hasNext}
              aria-label="Next listing"
              className="p-1.5 rounded-default text-on-surface-variant hover:bg-surface-container disabled:opacity-30 disabled:pointer-events-none"
            >
              →
            </button>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="p-1.5 rounded-default text-on-surface-variant hover:bg-surface-container"
          >
            ✕
          </button>
        </div>

        <div className="flex-1 overflow-y-auto px-5 py-4 flex flex-col gap-4">
          {isLoading && (
            <div className="text-body-sm text-on-surface-variant">Loading…</div>
          )}

          {listingRow && (
            <>
              {/* Header */}
              <div className="flex items-start justify-between gap-2 flex-wrap">
                <div className="flex flex-col gap-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="text-label-sm font-medium bg-error-container text-on-error-container px-2 py-0.5 rounded-full">
                      {openCount} open report{openCount !== 1 ? "s" : ""}
                    </span>
                    <span
                      className="text-label-sm px-2 py-0.5 rounded-full bg-surface-container text-on-surface-variant"
                      data-testid="auction-status-chip"
                    >
                      {listingRow.auctionStatus}
                    </span>
                  </div>
                  <div className="text-title-md font-semibold text-on-surface">
                    {listingRow.auctionTitle}
                  </div>
                  <div className="text-body-sm text-on-surface-variant">
                    Auction #{listingRow.auctionId}
                    {listingRow.sellerDisplayName && (
                      <span>
                        {" "}
                        · Seller:{" "}
                        <Link
                          href={`/admin/users/${listingRow.sellerUserId}`}
                          className="text-primary underline underline-offset-2"
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
                  <div className="text-label-md font-medium text-on-surface">
                    Reports ({reports.length})
                  </div>
                  {reports.map((report) => (
                    <AdminReportCard key={report.id} report={report} />
                  ))}
                </div>
              )}

              {reports && reports.length === 0 && (
                <div className="text-body-sm text-on-surface-variant">
                  No reports found for this listing.
                </div>
              )}

              {/* Shared notes */}
              <div className="flex flex-col gap-1">
                <label className="text-label-md font-medium text-on-surface">
                  Admin notes <span className="text-error">*</span>
                  <span className="text-on-surface-variant font-normal ml-1">
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
                  className="w-full resize-y rounded-default bg-surface-container px-4 py-3 text-on-surface placeholder:text-on-surface-variant ring-1 ring-outline-variant transition-all focus:outline-none focus:ring-primary disabled:opacity-50"
                />
                <div className="self-end text-label-sm text-on-surface-variant">
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
