package com.slparcelauctions.backend.auction.agentfee;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupWalletService;
import com.slparcelauctions.backend.wallet.WalletService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Group-sale payout splitter. Credits the listing agent's wallet with the agent slice and
 * the realty group's wallet with the residual group slice. Runs in the MANDATORY
 * transaction of {@code handleEscrowPayoutSuccess}; never opens its own transaction.
 *
 * <p>The pre-G sibling distributor for the "agent listing own land under a group" variant
 * was deleted by sub-project G. All realty-group listings post-G are group sales
 * (i.e. carry {@code realty_group_sl_group_id}); the group-listed-not-SL-group-owned
 * branch no longer exists.
 *
 * <p>Computation (spec §9.3):
 * <pre>
 *   earnings    = final_bid - platform_commission
 *   agent_slice = floor(earnings * agent_commission_rate)  &rarr; listing agent's wallet
 *   group_slice = earnings - agent_slice                   &rarr; group wallet
 * </pre>
 * Floor rounding matches D's existing convention; {@code agent_slice + group_slice == earnings}
 * exactly (no L$ lost to rounding).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentCommissionDistributor {

    private final WalletService walletService;
    private final RealtyGroupWalletService groupWalletService;

    @Transactional(propagation = Propagation.MANDATORY)
    public void distribute(Auction auction, long finalBid, long platformCommission) {
        if (auction.getRealtyGroupSlGroupId() == null) {
            throw new IllegalArgumentException(
                "AgentCommissionDistributor called on non-group-sale auction " + auction.getId());
        }
        BigDecimal rate = auction.getAgentCommissionRate();
        if (rate == null) {
            log.warn("auction {} has realty_group_sl_group_id set but agent_commission_rate is null; defaulting to 0",
                auction.getId());
            rate = BigDecimal.ZERO;
        }
        long earnings = finalBid - platformCommission;
        long agentSlice = BigDecimal.valueOf(earnings)
            .multiply(rate)
            .setScale(0, RoundingMode.FLOOR)
            .longValueExact();
        long groupSlice = earnings - agentSlice;

        if (agentSlice > 0 && auction.getListingAgent() != null) {
            walletService.creditAgentCommission(
                auction.getListingAgent().getId(), auction.getId(), agentSlice);
        }
        if (groupSlice > 0) {
            groupWalletService.creditPayout(
                auction.getRealtyGroupId(), auction.getId(), groupSlice);
        }
        log.info("agent-commission distribute: auction={} earnings={} rate={} agentSlice={} groupSlice={}",
            auction.getId(), earnings, rate, agentSlice, groupSlice);
    }
}
