"use client";

import { useEffect } from "react";
import { useQuery } from "@tanstack/react-query";
import { getGroupWallet } from "@/lib/api/realtyGroupWallet";
import { useRealtyGroup } from "@/hooks/realty/useRealtyGroups";
import { useAuth } from "@/lib/auth";

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
 * The agent's commission rate is sourced from the {@link useRealtyGroup}
 * fetch — the public group DTO embeds each member's {@code agentCommissionRate}
 * on {@code AgentCardDto} (visible to members because the caller is one).
 * The leader's commission rate is null on the leader card; that's never a
 * case-3 flow (leaders don't list under themselves through the agent
 * preview) so we treat a missing rate as 0 conservatively — the preview
 * still renders and shows the group taking the full earnings slice.
 */
export function AgentCommissionPreview({
  startingBid,
  groupName,
  groupPublicId,
  onInsufficient,
}: AgentCommissionPreviewProps) {
  const session = useAuth();
  const callerUserPublicId =
    session.status === "authenticated" ? session.user.publicId : null;

  const groupQ = useRealtyGroup(groupPublicId);

  // Find the caller's member row to pull their per-member commission rate.
  // Leaders surface as the group's {@code leader} block (no commission rate
  // field there); regular members live in {@code agents}. Default to 0 when
  // the caller can't be found in either bucket — the preview renders the
  // safest split (group keeps everything).
  const callerAgent =
    groupQ.data?.agents.find(
      (a) => a.userPublicId === callerUserPublicId,
    ) ?? null;
  const agentCommissionRate = callerAgent?.agentCommissionRate ?? 0;

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
