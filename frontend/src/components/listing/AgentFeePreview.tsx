"use client";

import { useEffect } from "react";
import { useQuery } from "@tanstack/react-query";
import { getGroupWallet } from "@/lib/api/realtyGroupWallet";

const COMMISSION_RATE = 0.05;

function floorLindens(bid: number, rate: number): number {
  return Math.floor(bid * rate);
}

export interface AgentFeePreviewProps {
  startingBid: number;
  groupName: string;
  agentFeeRate: number;
  /** publicId of the group the listing is being created under. When provided,
   * the component fetches the group wallet balance and renders a wallet-source
   * line. When null/undefined, the individual-listing path renders (no extra line). */
  groupPublicId?: string | null;
  /** Called whenever the "insufficient balance" state changes. The parent uses
   * this to gate the publish button. Only fired when groupPublicId is set. */
  onInsufficient?: (insufficient: boolean) => void;
}

export function AgentFeePreview({
  startingBid,
  groupName,
  agentFeeRate,
  groupPublicId,
  onInsufficient,
}: AgentFeePreviewProps) {
  const isGroupListing = groupPublicId != null && groupPublicId !== "";

  const walletQuery = useQuery({
    queryKey: ["realty", "group", groupPublicId, "wallet"],
    queryFn: () => getGroupWallet(groupPublicId!),
    staleTime: 30_000,
    refetchOnWindowFocus: true,
    enabled: isGroupListing,
  });
  const balance = walletQuery.data?.available ?? null;

  const commission = floorLindens(startingBid, COMMISSION_RATE);
  const agentFee = floorLindens(startingBid, agentFeeRate);
  const listingFee = commission; // listing fee == platform commission in Phase 1
  const payout = startingBid - commission - agentFee;
  const ratePct = (agentFeeRate * 100).toFixed(agentFeeRate < 0.01 ? 2 : 1).replace(/\.0$/, "");

  // Determine shortfall — only meaningful when we have a balance reading.
  const insufficient =
    isGroupListing && balance !== null && balance < listingFee;
  const shortfall = insufficient && balance !== null ? listingFee - balance : 0;

  useEffect(() => {
    if (!isGroupListing) return;
    onInsufficient?.(insufficient);
  }, [isGroupListing, insufficient, onInsufficient]);

  if (startingBid <= 0) return null;

  return (
    <div className="flex flex-col gap-1">
      <p className="text-sm text-gray-600 mt-2">
        If this lists at L${startingBid.toLocaleString()}, you{"'"}ll receive approximately{" "}
        <strong>L${payout.toLocaleString()}</strong> after platform commission (5%) and{" "}
        {groupName} agent fee ({ratePct}%).
      </p>
      {isGroupListing && (
        insufficient && balance !== null ? (
          <p className="text-sm text-danger mt-1">
            Group wallet has L${balance.toLocaleString()}; deposit L${shortfall.toLocaleString()} to publish.
          </p>
        ) : balance !== null ? (
          <p className="text-sm text-gray-500 mt-1">
            Listing fee paid from {groupName} wallet — current balance L${balance.toLocaleString()}.
          </p>
        ) : null
      )}
    </div>
  );
}
