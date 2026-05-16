"use client";

import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { isApiError } from "@/lib/api";
import { useToast } from "@/components/ui/Toast";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { BreadcrumbNav } from "@/components/auction/BreadcrumbNav";
import { ParcelInfoPanel } from "@/components/auction/ParcelInfoPanel";
import { VisitInSecondLifeBlock } from "@/components/auction/VisitInSecondLifeBlock";
import { ParcelLayoutMapPlaceholder } from "@/components/auction/ParcelLayoutMapPlaceholder";
import { BidHistoryList } from "@/components/auction/BidHistoryList";
import {
  SellerProfileCard,
  type SellerProfileCardSeller,
} from "@/components/auction/SellerProfileCard";
import { EditablePhotoGallery } from "@/components/listing/draft-editor/EditablePhotoGallery";
import { BidPanelPreview } from "@/components/listing/draft-editor/BidPanelPreview";
import { DraftActionBar } from "@/components/listing/draft-editor/DraftActionBar";
import { DeleteDraftModal } from "@/components/listing/draft-editor/DeleteDraftModal";
import { ConfirmListParcelModal } from "@/components/listing/draft-editor/ConfirmListParcelModal";
import {
  useDraftEditorMutations,
  type DraftSettings,
} from "@/components/listing/draft-editor/draftEditorMutations";
import { useReorderAuctionPhotos } from "@/hooks/useReorderAuctionPhotos";
import { uploadPhoto, deletePhoto } from "@/lib/api/auctionPhotos";
import { auctionKey } from "@/hooks/useAuction";
import { activateAuctionKey } from "@/hooks/useActivateAuction";
import { useListingFeeConfig } from "@/hooks/useListingFeeConfig";
import { useWallet, walletQueryKey } from "@/lib/wallet/use-wallet";
import {
  useGroupWallet,
  useInvalidateGroupWallet,
} from "@/hooks/realty/useGroupWallet";
import { payListingFee } from "@/lib/api/wallet";
import type {
  AuctionDurationHours,
  AuctionSnipeWindowMin,
  SellerAuctionResponse,
} from "@/types/auction";

function genIdempotencyKey(): string {
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

export interface DraftEditorClientProps {
  auction: SellerAuctionResponse;
}

/**
 * Rich real-page draft editor surface, rendered by ActivateClient when an
 * auction is in DRAFT. Mirrors the buyer's listing page composition (hero,
 * parcel info, visit-in-SL, layout map, bid history, seller card, right
 * rail) with inline click-to-edit, photo drag-reorder, and a sticky top
 * action bar carrying the listing-fee + List Parcel + Delete Draft
 * actions.
 */
export function DraftEditorClient({ auction }: DraftEditorClientProps) {
  const qc = useQueryClient();
  const toast = useToast();
  const m = useDraftEditorMutations(auction.publicId);
  const reorder = useReorderAuctionPhotos(auction.publicId);
  const feeQ = useListingFeeConfig();
  const groupPublicId = auction.realtyGroup?.publicId ?? null;
  const isGroupListing = groupPublicId !== null;
  // Only one of these is meaningfully populated: the user wallet drives
  // personal listings, the group wallet drives group-attributed ones.
  // Backend MeWalletController.payListingFee routes the debit the same
  // way (auction.realtyGroupId → group wallet, else user wallet) so this
  // mirrors the source of truth.
  const walletQ = useWallet(!isGroupListing);
  const groupWalletQ = useGroupWallet(groupPublicId ?? "");
  const invalidateGroupWallet = useInvalidateGroupWallet(groupPublicId ?? "");
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [confirmListOpen, setConfirmListOpen] = useState(false);

  const listMutation = useMutation({
    mutationFn: () => payListingFee(auction.publicId, genIdempotencyKey()),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: activateAuctionKey(auction.publicId) });
      if (isGroupListing) {
        invalidateGroupWallet();
      } else {
        qc.invalidateQueries({ queryKey: walletQueryKey });
      }
      toast.success("Listing fee paid. Choose a verification method next.");
      setConfirmListOpen(false);
    },
    onError: (e) => {
      const detail = isApiError(e)
        ? e.problem.detail ?? e.problem.title ?? "Could not list this parcel."
        : e instanceof Error
          ? e.message
          : "Could not list this parcel.";
      toast.error(detail);
      if (isGroupListing) {
        invalidateGroupWallet();
      } else {
        qc.invalidateQueries({ queryKey: walletQueryKey });
      }
    },
  });

  const walletLoading = isGroupListing ? groupWalletQ.isLoading : walletQ.isLoading;
  const walletData = isGroupListing ? groupWalletQ.data : walletQ.data;
  const walletError = isGroupListing ? groupWalletQ.error : walletQ.error;
  if (feeQ.isLoading || walletLoading || !feeQ.data) {
    return (
      <div className="mx-auto max-w-3xl p-6">
        <LoadingSpinner label="Loading listing details…" />
      </div>
    );
  }
  if (!walletData) {
    // Group-listed drafts require the seller to be able to read the
    // group wallet — leader auto-passes; agents need VIEW_GROUP_TRANSACTIONS.
    // Surface the failure rather than spinning forever.
    const detail = isApiError(walletError)
      ? walletError.problem.detail ??
        walletError.problem.title ??
        "Could not load wallet."
      : walletError instanceof Error
        ? walletError.message
        : isGroupListing
          ? "Could not load the group wallet. You may need VIEW_GROUP_TRANSACTIONS permission to list under this group."
          : "Could not load wallet.";
    return (
      <div className="mx-auto max-w-3xl p-6 text-sm text-danger">
        {detail}
      </div>
    );
  }

  const fee = feeQ.data.amountLindens;
  const availableBalance = walletData.available;
  // Group wallet has no penalty concept — only the user wallet does.
  // For group listings we gate purely on balance; for user listings we
  // also block when the user has an outstanding penalty.
  const personalPenaltyOwed = isGroupListing
    ? 0
    : (walletQ.data?.penaltyOwed ?? 0);
  const insufficientFunds = availableBalance < fee || personalPenaltyOwed > 0;

  const settings: DraftSettings = {
    startingBid: auction.startingBid,
    reservePrice: auction.reservePrice,
    buyNowPrice: auction.buyNowPrice,
    durationHours: auction.durationHours as AuctionDurationHours,
    snipeProtect: auction.snipeProtect,
    snipeWindowMin: auction.snipeWindowMin as AuctionSnipeWindowMin | null,
  };

  const sellerCardData: SellerProfileCardSeller = auction.seller
    ? {
        publicId: auction.seller.publicId,
        displayName: auction.seller.displayName,
        avatarUrl: auction.seller.avatarUrl,
        averageRating: auction.seller.averageRating,
        reviewCount: auction.seller.reviewCount,
        completedSales: auction.seller.completedSales,
        completionRate: auction.seller.completionRate,
        memberSince: auction.seller.memberSince,
      }
    : {
        publicId: auction.sellerPublicId,
        displayName: "You",
        completedSales: 0,
      };

  function refreshAuctionAfterPhotoOp() {
    qc.invalidateQueries({ queryKey: auctionKey(auction.publicId) });
    qc.invalidateQueries({ queryKey: activateAuctionKey(auction.publicId) });
  }

  return (
    <div data-testid="draft-editor-client" className="flex flex-col gap-3">
      <DraftActionBar
        listingFee={fee}
        walletBalance={availableBalance}
        isGroupListing={isGroupListing}
        isListing={listMutation.isPending}
        insufficientFunds={insufficientFunds}
        onListParcel={() => setConfirmListOpen(true)}
        onDeleteDraft={() => setDeleteOpen(true)}
      />
      <main className="max-w-7xl mx-auto px-4 lg:px-8 pt-4 pb-24 lg:pb-12 w-full">
        <div className="mt-4">
          <BreadcrumbNav
            region={auction.parcel.regionName}
            title={auction.title}
          />
        </div>
        <div className="mt-6 grid grid-cols-1 lg:grid-cols-12 gap-6 lg:gap-12">
          <div className="lg:col-span-8 space-y-8 lg:space-y-12">
            <EditablePhotoGallery
              photos={auction.photos}
              snapshotUrl={auction.parcel.snapshotUrl}
              regionName={auction.parcel.regionName}
              onReorder={async (orderedPublicIds) => {
                await reorder.mutateAsync(orderedPublicIds);
              }}
              onDelete={async (photoPublicId) => {
                await deletePhoto(auction.publicId, photoPublicId);
                refreshAuctionAfterPhotoOp();
              }}
              onAdd={async (file) => {
                await uploadPhoto(auction.publicId, file);
                refreshAuctionAfterPhotoOp();
              }}
            />
            <ParcelInfoPanel
              auction={auction}
              editable={{
                onTitleChange: m.saveTitle,
                onDescriptionChange: m.saveDescription,
                onTagsChange: m.saveTags,
              }}
            />
            <VisitInSecondLifeBlock
              regionName={auction.parcel.regionName}
              positionX={auction.parcel.positionX}
              positionY={auction.parcel.positionY}
              positionZ={auction.parcel.positionZ}
            />
            <ParcelLayoutMapPlaceholder />
            <BidHistoryList auctionPublicId={auction.publicId} />
            <SellerProfileCard seller={sellerCardData} />
          </div>
          <aside className="hidden lg:block lg:col-span-4">
            <div className="sticky top-24">
              <BidPanelPreview
                settings={settings}
                onSettingsChange={m.saveSettings}
              />
            </div>
          </aside>
        </div>
        <div className="lg:hidden mt-8">
          <BidPanelPreview
            settings={settings}
            onSettingsChange={m.saveSettings}
          />
        </div>
      </main>
      <DeleteDraftModal
        open={deleteOpen}
        onClose={() => setDeleteOpen(false)}
        auction={auction}
      />
      <ConfirmListParcelModal
        open={confirmListOpen}
        onClose={() => setConfirmListOpen(false)}
        onConfirm={() => listMutation.mutate()}
        listingFee={fee}
        walletBalance={availableBalance}
        isGroupListing={isGroupListing}
        isListing={listMutation.isPending}
      />
    </div>
  );
}
