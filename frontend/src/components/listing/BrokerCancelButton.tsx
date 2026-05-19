"use client";

import { useMemo, useState } from "react";
import { Button } from "@/components/ui/Button";
import { useAuth } from "@/lib/auth";
import { useRealtyGroup } from "@/hooks/realty/useRealtyGroups";
import type { PublicAuctionResponse, SellerAuctionResponse } from "@/types/auction";
import { BrokerCancelModal } from "./BrokerCancelModal";

export interface BrokerCancelButtonProps {
  auction: PublicAuctionResponse | SellerAuctionResponse;
}

/**
 * Statuses where a broker can still cancel — mirrors the backend
 * {@code CancellationService.CANCELLABLE} set. DRAFT through ACTIVE; any
 * status past ENDED is terminal. Defensive duplication so the affordance
 * never renders when the backend would reject the call anyway.
 */
const BROKER_CANCELLABLE = new Set([
  "DRAFT",
  "DRAFT_PAID",
  "VERIFICATION_PENDING",
  "VERIFICATION_FAILED",
  "ACTIVE",
]);

/**
 * Renders the "Cancel listing" affordance for group-sale brokers — members of
 * the realty group who hold {@code MANAGE_ALL_LISTINGS} and are NOT the
 * auction's seller. The button is hidden in three cases:
 *
 * <ul>
 *   <li>Auction has no realty group (individual listing) — no broker path.</li>
 *   <li>Caller is the seller — the seller's own
 *     {@code CancelListingModal} owns that flow.</li>
 *   <li>Caller doesn't hold {@code MANAGE_ALL_LISTINGS} on the group, or
 *       isn't a member at all.</li>
 *   <li>Auction is in a terminal state (ENDED, COMPLETED, etc.).</li>
 * </ul>
 *
 * The permission check uses the group's public DTO ({@link useRealtyGroup})
 * to find the caller's member row + permissions array. Leader rows
 * implicitly hold every permission, mirroring the backend authorizer.
 *
 * Realty Groups: E §6.5.
 */
export function BrokerCancelButton({ auction }: BrokerCancelButtonProps) {
  const [open, setOpen] = useState(false);
  const session = useAuth();
  const callerPublicId =
    session.status === "authenticated" ? session.user.publicId : null;

  const realtyGroupPublicId = auction.realtyGroup?.publicId;
  const groupQ = useRealtyGroup(realtyGroupPublicId);

  const hasManageAllListings = useMemo(() => {
    if (!callerPublicId || !groupQ.data) return false;
    // Leader holds every permission implicitly (matches backend authorizer
    // short-circuit on user_id == leader_id).
    if (groupQ.data.leader.userPublicId === callerPublicId) return true;
    const callerAgent = groupQ.data.agents.find(
      (a) => a.userPublicId === callerPublicId,
    );
    return callerAgent?.permissions?.includes("MANAGE_ALL_LISTINGS") ?? false;
  }, [callerPublicId, groupQ.data]);

  // Visibility gates (top-down, first false wins):
  //   1. Auction must be group-attributed.
  //   2. Auction must be in a cancellable status.
  //   3. Caller must be authenticated.
  //   4. Caller must NOT be the seller.
  //   5. Caller must hold MANAGE_ALL_LISTINGS (or be the leader).
  if (!auction.realtyGroup) return null;
  if (!BROKER_CANCELLABLE.has(auction.status)) return null;
  if (callerPublicId == null) return null;
  if (callerPublicId === auction.sellerPublicId) return null;
  if (!hasManageAllListings) return null;

  return (
    <>
      <Button
        type="button"
        variant="destructive"
        size="sm"
        onClick={() => setOpen(true)}
        data-testid="broker-cancel-button"
      >
        Cancel listing
      </Button>
      <BrokerCancelModal
        open={open}
        onClose={() => setOpen(false)}
        auction={auction}
      />
    </>
  );
}
