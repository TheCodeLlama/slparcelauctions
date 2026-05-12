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
 * Distributes {@code agent_fee_amt} at escrow-payout-success time.
 *
 * <p>{@code floor(agent_fee_amt * agent_fee_split)} credits the group wallet;
 * the remainder credits the listing agent's user wallet. Spec §7.2.
 *
 * <p>Called from {@code TerminalCommandService.handleEscrowPayoutSuccess} inside
 * an already-open transaction. {@link Propagation#MANDATORY} enforces this.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentFeeDistributor {

    private final RealtyGroupWalletService groupWalletService;
    private final WalletService userWalletService;

    @Transactional(propagation = Propagation.MANDATORY)
    public void distribute(Auction auction, long agentFeeAmt) {
        if (agentFeeAmt <= 0) {
            return;
        }
        BigDecimal split = auction.getAgentFeeSplit() == null
            ? BigDecimal.ZERO : auction.getAgentFeeSplit();

        long groupSlice = BigDecimal.valueOf(agentFeeAmt)
            .multiply(split)
            .setScale(0, RoundingMode.FLOOR)
            .longValueExact();
        long agentSlice = agentFeeAmt - groupSlice;

        Long groupId = auction.getRealtyGroupId();
        Long agentId = auction.getListingAgent() != null
            ? auction.getListingAgent().getId() : null;

        if (groupId == null && groupSlice > 0) {
            log.warn("agent fee groupSlice={} but realty_group_id null on auction {}; absorbed by seller payout",
                groupSlice, auction.getId());
        }
        if (agentId == null && agentSlice > 0) {
            log.warn("agent fee agentSlice={} but listing_agent_id null on auction {}; absorbed by seller payout",
                agentSlice, auction.getId());
        }

        if (groupId != null && groupSlice > 0) {
            groupWalletService.creditAgentFee(groupId, auction.getId(), groupSlice);
        }
        if (agentId != null && agentSlice > 0) {
            userWalletService.creditAgentFee(agentId, auction.getId(), agentSlice);
        }
    }
}
