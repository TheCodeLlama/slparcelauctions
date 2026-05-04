"use client";
import { useState } from "react";
import { useAuth } from "@/lib/auth";
import { useMyReport } from "@/hooks/auction/useMyReport";
import { ReportListingModal } from "./ReportListingModal";

type Props = {
  auctionPublicId: string;
  sellerPublicId: string;
};

export function ReportListingButton({ auctionPublicId, sellerPublicId }: Props) {
  const [modalOpen, setModalOpen] = useState(false);
  const session = useAuth();
  const myReportQuery = useMyReport(
    session.status === "authenticated" ? auctionPublicId : null
  );

  // Loading state — render nothing to avoid layout flicker.
  if (session.status === "loading") return null;

  // Anonymous — don't show the button at all.
  if (session.status === "unauthenticated") return null;

  // Seller's own listing — hide button.
  if (session.user.publicId === sellerPublicId) return null;

  const unverified = !session.user.verified;

  // Determine disabled state from existing report status.
  const myReport = myReportQuery.data ?? null;
  const alreadyReported =
    myReport !== null &&
    myReport.status !== "DISMISSED";

  const disabled = unverified || alreadyReported;

  const label = alreadyReported ? "Reported ✓" : "Report";

  const title = unverified
    ? "Verify your SL avatar to report listings"
    : undefined;

  return (
    <>
      <button
        type="button"
        disabled={disabled}
        title={title}
        onClick={() => setModalOpen(true)}
        data-testid="report-listing-btn"
        className="px-3 py-1.5 rounded-lg text-[11px] font-medium text-fg-muted bg-bg-muted hover:bg-bg-hover disabled:opacity-50 disabled:pointer-events-none transition-colors"
      >
        {label}
      </button>

      {modalOpen && (
        <ReportListingModal
          auctionPublicId={auctionPublicId}
          onClose={() => setModalOpen(false)}
        />
      )}
    </>
  );
}
