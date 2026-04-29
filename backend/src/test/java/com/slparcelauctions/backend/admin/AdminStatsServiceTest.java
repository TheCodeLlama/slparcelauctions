package com.slparcelauctions.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.admin.dto.AdminStatsResponse;
import com.slparcelauctions.backend.admin.reports.ListingReportRepository;
import com.slparcelauctions.backend.admin.reports.ListingReportStatus;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class AdminStatsServiceTest {

    @Mock UserRepository userRepository;
    @Mock AuctionRepository auctionRepository;
    @Mock EscrowRepository escrowRepository;
    @Mock FraudFlagRepository fraudFlagRepository;
    @Mock ListingReportRepository listingReportRepository;

    @InjectMocks AdminStatsService service;

    @Test
    void compute_assemblesAllTenNumbers() {
        when(fraudFlagRepository.countByResolved(false)).thenReturn(7L);
        when(listingReportRepository.countByStatus(ListingReportStatus.OPEN)).thenReturn(5L);
        when(escrowRepository.countByState(EscrowState.ESCROW_PENDING)).thenReturn(3L);
        when(escrowRepository.countByState(EscrowState.DISPUTED)).thenReturn(1L);
        when(auctionRepository.countByStatus(AuctionStatus.ACTIVE)).thenReturn(42L);
        when(userRepository.count()).thenReturn(381L);
        when(escrowRepository.countByStateNotIn(any())).thenReturn(12L);
        when(escrowRepository.countByState(EscrowState.COMPLETED)).thenReturn(156L);
        when(escrowRepository.sumFinalBidAmountByState(EscrowState.COMPLETED)).thenReturn(4_827_500L);
        when(escrowRepository.sumCommissionAmtByState(EscrowState.COMPLETED)).thenReturn(241_375L);

        AdminStatsResponse result = service.compute();

        assertThat(result.queues().openFraudFlags()).isEqualTo(7L);
        assertThat(result.queues().openReports()).isEqualTo(5L);
        assertThat(result.queues().pendingPayments()).isEqualTo(3L);
        assertThat(result.queues().activeDisputes()).isEqualTo(1L);
        assertThat(result.platform().activeListings()).isEqualTo(42L);
        assertThat(result.platform().totalUsers()).isEqualTo(381L);
        assertThat(result.platform().activeEscrows()).isEqualTo(12L);
        assertThat(result.platform().completedSales()).isEqualTo(156L);
        assertThat(result.platform().lindenGrossVolume()).isEqualTo(4_827_500L);
        assertThat(result.platform().lindenCommissionEarned()).isEqualTo(241_375L);
    }

    @Test
    void compute_activeEscrows_excludesAllTerminalStates() {
        Set<EscrowState> terminal = Set.of(
            EscrowState.COMPLETED, EscrowState.EXPIRED,
            EscrowState.DISPUTED, EscrowState.FROZEN);
        when(fraudFlagRepository.countByResolved(false)).thenReturn(0L);
        when(listingReportRepository.countByStatus(any())).thenReturn(0L);
        when(escrowRepository.countByState(any())).thenReturn(0L);
        when(auctionRepository.countByStatus(any())).thenReturn(0L);
        when(userRepository.count()).thenReturn(0L);
        when(escrowRepository.countByStateNotIn(terminal)).thenReturn(99L);
        when(escrowRepository.sumFinalBidAmountByState(any())).thenReturn(0L);
        when(escrowRepository.sumCommissionAmtByState(any())).thenReturn(0L);

        AdminStatsResponse result = service.compute();

        assertThat(result.platform().activeEscrows()).isEqualTo(99L);
    }
}
