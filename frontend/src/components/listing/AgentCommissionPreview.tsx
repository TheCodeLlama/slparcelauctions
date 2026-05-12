"use client";

import { useEffect } from "react";
import { useQuery } from "@tanstack/react-query";
import { getGroupWallet } from "@/lib/api/realtyGroupWallet";

const PLATFORM_COMMISSION_RATE = 0.05;

/**
 * Floor-rounded slice — mirrors the backend's {@code Math.floor(...)} math
 * for L$ payouts so the preview never overstates what the agent will see.
 */
function floorLindens(amount: number, rate: number): number {
  return Math.floor(amount * rate);
}

export interface AgentCommissionPreviewProps {
  startingBid: number;
  groupName: string;
  /** publicId of the realty group the listing is being created under. */
  groupPublicId: string;
  /**
   * Caller's per-member commission rate within the group, sourced directly
   * from the {@code listing-eligible-groups} row (sub-project G section 6.2).
   * The wizard hands it in so this component no longer round-trips through
   * {@code useRealtyGroup}.
   */
  agentCommissionRate: number;
  /**
   * Called whenever the "insufficient balance" state changes. The parent
   * uses this to gate the publish button. The listing fee == platform
   * commission == floor(startingBid * 0.05) in Phase 1.
   */
  onInsufficient?: (insufficient: boolean) => void;
}

/**
 * Case-3 ("Realty Groups: E") fee preview for the listing wizard.
 *
 * Case 3 is an agent listing a group-owned parcel under the realty group:
 * the platform takes a 5% commission off the starting bid, then the
 * remaining earnings are split between the agent and the group per the
 * agent's per-member {@code agentCommissionRate}. The group wallet pays the
 * listing fee (which equals the platform commission in Phase 1).
 *
 * The agent's commission rate arrives as a prop from the wizard, which
 * reads it off the eligible-list row the user picked in
 * {@code ListAsGroupPicker}. This removes the prior {@code useRealtyGroup}
 * round-trip that re-fetched the public group DTO solely to read the
 * caller's member row.
 */
export function AgentCommissionPreview({
  startingBid,
  groupName,
  groupPublicId,
  agentCommissionRate,
  onInsufficient,
}: AgentCommissionPreviewProps) {
  const walletQuery = useQuery({
    queryKey: ["realty", "group", groupPublicId, "wallet"],
    queryFn: () => getGroupWallet(groupPublicId),
    staleTime: 30_000,
    refetchOnWindowFocus: true,
  });
  const balance = walletQuery.data?.available ?? null;

  // Case-3 math (spec §6.3):
  //   platformCommission = floor(startingBid * 0.05)
  //   earnings           = startingBid - platformCommission
  //   agentSlice         = floor(earnings * agentCommissionRate)
  //   groupSlice         = earnings - agentSlice
  const platformCommission = floorLindens(startingBid, PLATFORM_COMMISSION_RATE);
  const earnings = startingBid - platformCommission;
  const agentSlice = floorLindens(earnings, agentCommissionRate);
  const groupSlice = earnings - agentSlice;
  // Listing fee equals the platform commission in Phase 1 — the group
  // wallet pays it up-front (refundable per cancellation rules).
  const listingFee = platformCommission;
  const insufficient = balance !== null && balance < listingFee;
  const shortfall = insufficient && balance !== null ? listingFee - balance : 0;

  useEffect(() => {
    onInsufficient?.(insufficient);
  }, [insufficient, onInsufficient]);

  if (startingBid <= 0) return null;

  // Render the rate as a 1- or 2-decimal percentage, trimming trailing
  // zeros so 0.10 → "10%" and 0.075 → "7.5%". Matches the case-1
  // AgentFeePreview formatting so the two previews feel consistent.
  const ratePct = (agentCommissionRate * 100)
    .toFixed(agentCommissionRate < 0.01 ? 2 : 1)
    .replace(/\.0$/, "");

  return (
    <div className="flex flex-col gap-1" data-testid="agent-commission-preview">
      <p className="text-sm text-gray-600 mt-2">
        If this lists at L${startingBid.toLocaleString()}, you{"'"}ll receive{" "}
        <strong>L${agentSlice.toLocaleString()}</strong> (your {ratePct}%
        commission of earnings after platform commission).{" "}
        <strong>{groupName}</strong> earns{" "}
        <strong>L${groupSlice.toLocaleString()}</strong>.
      </p>
      {insufficient && balance !== null ? (
        <p className="text-sm text-danger mt-1">
          Group wallet has L${balance.toLocaleString()}; deposit L$
          {shortfall.toLocaleString()} to publish.
        </p>
      ) : balance !== null ? (
        <p className="text-sm text-gray-500 mt-1">
          Listing fee paid from {groupName} wallet — current balance L$
          {balance.toLocaleString()}.
        </p>
      ) : null}
    </div>
  );
}
