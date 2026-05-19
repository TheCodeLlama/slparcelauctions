"use client";

import { useEffect, useState } from "react";
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
  /**
   * Reserve price for the auction settings, or null when none is set.
   * Only feeds the Sell Price what-if default; never the listing fee.
   */
  reservePrice: number | null;
  /**
   * Buy-it-now price for the auction settings, or null when none is set.
   * Only feeds the Sell Price what-if default; never the listing fee.
   */
  buyNowPrice: number | null;
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
 * Group-sale fee preview for the listing wizard.
 *
 * Group sale = an agent listing a group-owned parcel under the realty group:
 * the platform takes a 5% commission, then the remaining earnings are split
 * between the agent and the group per the agent's per-member
 * {@code agentCommissionRate}. The visible projection rows (platform
 * commission, your earnings, group earnings) recompute from the editable
 * Sell Price input (default {@code buyNowPrice ?? reservePrice ??
 * startingBid}). The group wallet pays the listing fee, which stays
 * decoupled from the Sell Price input: it remains {@code floor(startingBid *
 * 0.05)} (== the platform commission on the starting bid in Phase 1) and is
 * also what drives the insufficient-balance publish gating.
 *
 * The agent's commission rate arrives as a prop from the wizard, which
 * reads it off the eligible-list row the user picked in
 * {@code ListAsGroupPicker}. This removes the prior {@code useRealtyGroup}
 * round-trip that re-fetched the public group DTO solely to read the
 * caller's member row.
 */
export function AgentCommissionPreview({
  startingBid,
  reservePrice,
  buyNowPrice,
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

  // Sell Price is a pure what-if projection (spec Area 1). It defaults to
  // Buy It Now, then Reserve, then Starting Bid, and re-seeds while the
  // seller has not touched it; once edited it is fully seller-controlled.
  const defaultSell = buyNowPrice ?? reservePrice ?? startingBid;
  const [raw, setRaw] = useState<string>(String(defaultSell));
  const [touched, setTouched] = useState(false);
  useEffect(() => {
    if (!touched) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setRaw(String(defaultSell));
    }
  }, [defaultSell, touched]);
  const parsedSell = raw.trim() === "" ? defaultSell : Math.floor(Number(raw));
  const sellPrice =
    Number.isFinite(parsedSell) && parsedSell > 0 ? parsedSell : defaultSell;

  // Displayed projection recomputes from the what-if Sell Price (spec §6.3
  // math, sellPrice substituted for the old static list price):
  //   platformCommission = floor(sellPrice * 0.05)
  //   earnings           = sellPrice - platformCommission
  //   agentSlice         = floor(earnings * agentCommissionRate)
  //   groupSlice         = earnings - agentSlice
  const platformCommission = floorLindens(sellPrice, PLATFORM_COMMISSION_RATE);
  const earnings = sellPrice - platformCommission;
  const agentSlice = floorLindens(earnings, agentCommissionRate);
  const groupSlice = earnings - agentSlice;
  // Listing fee / publish gating stays DECOUPLED from the Sell Price
  // what-if: it is always floor(startingBid * 0.05) (== the platform
  // commission on the starting bid in Phase 1). The group wallet pays it
  // up-front (refundable per cancellation rules). Editing Sell Price must
  // never change what is charged or gated.
  const listingFee = floorLindens(startingBid, PLATFORM_COMMISSION_RATE);
  const insufficient = balance !== null && balance < listingFee;
  const shortfall = insufficient && balance !== null ? listingFee - balance : 0;

  useEffect(() => {
    onInsufficient?.(insufficient);
  }, [insufficient, onInsufficient]);

  if (startingBid <= 0) return null;

  // Render the rate as a 1- or 2-decimal percentage, trimming trailing
  // zeros so 0.10 → "10%" and 0.075 → "7.5%". Matches the legacy
  // AgentFeePreview formatting so the two previews feel consistent.
  const ratePct = (agentCommissionRate * 100)
    .toFixed(agentCommissionRate < 0.01 ? 2 : 1)
    .replace(/\.0$/, "");

  return (
    <div className="flex flex-col gap-2 mt-2" data-testid="agent-commission-preview">
      <dl className="flex flex-col rounded-lg bg-bg-subtle px-4 py-3 text-sm">
        <div className="flex justify-between gap-4 py-1">
          <dt className="text-fg-muted">Sell Price</dt>
          <dd>
            <input
              type="number"
              inputMode="numeric"
              min={1}
              step={1}
              aria-label="Sell price (L$)"
              data-testid="sell-price-input"
              value={raw}
              onChange={(e) => {
                setTouched(true);
                setRaw(e.target.value);
              }}
              className="w-28 rounded-md bg-surface-raised px-2 py-1 text-right tabular-nums font-medium text-fg ring-1 ring-transparent focus:outline-none focus:ring-2 focus:ring-brand"
            />
          </dd>
        </div>
        <div className="flex justify-between gap-4 py-1">
          <dt className="text-fg-muted">Platform commission at sell price</dt>
          <dd className="tabular-nums font-medium text-fg">
            L${platformCommission.toLocaleString()}{" "}
            <span className="text-fg-muted font-normal">(5%)</span>
          </dd>
        </div>
        <div className="flex justify-between gap-4 py-1">
          <dt className="text-fg-muted">Your earnings at sell price</dt>
          <dd className="tabular-nums font-medium text-fg">
            L${agentSlice.toLocaleString()}{" "}
            <span className="text-fg-muted font-normal">
              ({ratePct}% of remaining)
            </span>
          </dd>
        </div>
        <div className="flex justify-between gap-4 py-1">
          <dt className="text-fg-muted">{groupName} earnings at sell price</dt>
          <dd className="tabular-nums font-medium text-fg">
            L${groupSlice.toLocaleString()}{" "}
            <span className="text-fg-muted font-normal">(remaining)</span>
          </dd>
        </div>
      </dl>
      {insufficient && balance !== null ? (
        <p className="text-sm text-danger">
          Group wallet has L${balance.toLocaleString()}; deposit L$
          {shortfall.toLocaleString()} to publish.
        </p>
      ) : balance !== null ? (
        <p className="text-xs text-fg-muted">
          Listing fee paid from {groupName} wallet. Current balance L$
          {balance.toLocaleString()}.
        </p>
      ) : null}
    </div>
  );
}
