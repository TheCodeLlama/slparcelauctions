package com.slparcelauctions.backend.admin;

import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.dto.AdminStatsResponse;
import com.slparcelauctions.backend.admin.dto.AdminStatsResponse.PlatformStats;
import com.slparcelauctions.backend.admin.dto.AdminStatsResponse.QueueStats;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AdminStatsService {

    private static final Set<EscrowState> TERMINAL_ESCROW_STATES = Set.of(
        EscrowState.COMPLETED,
        EscrowState.EXPIRED,
        EscrowState.DISPUTED,
        EscrowState.FROZEN
    );

    private final UserRepository userRepository;
    private final AuctionRepository auctionRepository;
    private final EscrowRepository escrowRepository;
    private final FraudFlagRepository fraudFlagRepository;

    @Transactional(readOnly = true)
    public AdminStatsResponse compute() {
        QueueStats queues = new QueueStats(
            fraudFlagRepository.countByResolved(false),
            escrowRepository.countByState(EscrowState.ESCROW_PENDING),
            escrowRepository.countByState(EscrowState.DISPUTED)
        );

        PlatformStats platform = new PlatformStats(
            auctionRepository.countByStatus(AuctionStatus.ACTIVE),
            userRepository.count(),
            escrowRepository.countByStateNotIn(TERMINAL_ESCROW_STATES),
            escrowRepository.countByState(EscrowState.COMPLETED),
            escrowRepository.sumFinalBidAmountByState(EscrowState.COMPLETED),
            escrowRepository.sumCommissionAmtByState(EscrowState.COMPLETED)
        );

        return new AdminStatsResponse(queues, platform);
    }
}
