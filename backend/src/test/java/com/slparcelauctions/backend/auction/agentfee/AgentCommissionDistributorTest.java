package com.slparcelauctions.backend.auction.agentfee;

import java.lang.reflect.Field;
import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupWalletService;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.wallet.WalletService;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AgentCommissionDistributorTest {

    private final WalletService userSvc = mock(WalletService.class);
    private final RealtyGroupWalletService groupSvc = mock(RealtyGroupWalletService.class);
    private final AgentCommissionDistributor dist =
        new AgentCommissionDistributor(userSvc, groupSvc);

    /** Build a User with a specific id set via reflection. */
    private static User userWithId(long id) throws Exception {
        User u = User.builder().username("agent").passwordHash("x").build();
        Field idField = com.slparcelauctions.backend.common.BaseEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(u, id);
        return u;
    }

    @Test
    void distribute_happyPath_creditsAgentAndGroupCorrectly() throws Exception {
        Auction a = mock(Auction.class);
        when(a.getRealtyGroupSlGroupId()).thenReturn(123L);
        when(a.getRealtyGroupId()).thenReturn(42L);
        when(a.getAgentCommissionRate()).thenReturn(new BigDecimal("0.10"));
        when(a.getId()).thenReturn(999L);
        User agent = userWithId(7L);
        when(a.getListingAgent()).thenReturn(agent);

        // finalBid=10000, platformCommission=500, earnings=9500.
        // agentSlice = floor(9500 * 0.10) = 950, groupSlice = 8550.
        dist.distribute(a, 10000L, 500L);

        verify(userSvc).creditAgentCommission(7L, 999L, 950L);
        verify(groupSvc).creditPayout(42L, 999L, 8550L);
    }

    @Test
    void distribute_zeroRate_allToGroup() throws Exception {
        Auction a = mock(Auction.class);
        when(a.getRealtyGroupSlGroupId()).thenReturn(123L);
        when(a.getRealtyGroupId()).thenReturn(42L);
        when(a.getAgentCommissionRate()).thenReturn(BigDecimal.ZERO);
        when(a.getId()).thenReturn(999L);
        User agent = userWithId(7L);
        when(a.getListingAgent()).thenReturn(agent);

        // earnings = 9500. agentSlice = 0, groupSlice = 9500.
        dist.distribute(a, 10000L, 500L);

        verify(userSvc, never()).creditAgentCommission(anyLong(), anyLong(), anyLong());
        verify(groupSvc).creditPayout(42L, 999L, 9500L);
    }

    @Test
    void distribute_fullRate_allToAgent() throws Exception {
        Auction a = mock(Auction.class);
        when(a.getRealtyGroupSlGroupId()).thenReturn(123L);
        when(a.getRealtyGroupId()).thenReturn(42L);
        when(a.getAgentCommissionRate()).thenReturn(new BigDecimal("1.0"));
        when(a.getId()).thenReturn(999L);
        User agent = userWithId(7L);
        when(a.getListingAgent()).thenReturn(agent);

        // earnings = 9500. agentSlice = 9500, groupSlice = 0.
        dist.distribute(a, 10000L, 500L);

        verify(userSvc).creditAgentCommission(7L, 999L, 9500L);
        verify(groupSvc, never()).creditPayout(anyLong(), anyLong(), anyLong());
    }

    @Test
    void distribute_throwsIfCase1Auction() {
        Auction a = mock(Auction.class);
        when(a.getRealtyGroupSlGroupId()).thenReturn(null);
        when(a.getId()).thenReturn(999L);

        assertThrows(IllegalArgumentException.class, () -> dist.distribute(a, 10000L, 500L));

        verifyNoInteractions(userSvc);
        verifyNoInteractions(groupSvc);
    }

    @Test
    void distribute_nullRate_treatedAsZero() throws Exception {
        Auction a = mock(Auction.class);
        when(a.getRealtyGroupSlGroupId()).thenReturn(123L);
        when(a.getRealtyGroupId()).thenReturn(42L);
        when(a.getAgentCommissionRate()).thenReturn(null);
        when(a.getId()).thenReturn(999L);
        User agent = userWithId(7L);
        when(a.getListingAgent()).thenReturn(agent);

        // earnings = 9500. rate null -> 0. agentSlice = 0, groupSlice = 9500.
        dist.distribute(a, 10000L, 500L);

        verify(userSvc, never()).creditAgentCommission(anyLong(), anyLong(), anyLong());
        verify(groupSvc).creditPayout(42L, 999L, 9500L);
    }

    @Test
    void distribute_nullAgent_skipsAgentCredit() {
        Auction a = mock(Auction.class);
        when(a.getRealtyGroupSlGroupId()).thenReturn(123L);
        when(a.getRealtyGroupId()).thenReturn(42L);
        when(a.getAgentCommissionRate()).thenReturn(new BigDecimal("0.10"));
        when(a.getId()).thenReturn(999L);
        when(a.getListingAgent()).thenReturn(null);

        // earnings = 9500. agentSlice = 950 but no agent -> skipped; group still gets 8550.
        dist.distribute(a, 10000L, 500L);

        verify(userSvc, never()).creditAgentCommission(anyLong(), anyLong(), anyLong());
        verify(groupSvc).creditPayout(42L, 999L, 8550L);
    }

    @Test
    void distribute_floorRoundingOnOddSplit() throws Exception {
        Auction a = mock(Auction.class);
        when(a.getRealtyGroupSlGroupId()).thenReturn(123L);
        when(a.getRealtyGroupId()).thenReturn(42L);
        when(a.getAgentCommissionRate()).thenReturn(new BigDecimal("0.5"));
        when(a.getId()).thenReturn(999L);
        User agent = userWithId(7L);
        when(a.getListingAgent()).thenReturn(agent);

        // earnings = 51. agentSlice = floor(51 * 0.5) = 25, groupSlice = 26.
        // No L$ lost: 25 + 26 == 51.
        dist.distribute(a, 100L, 49L);

        verify(userSvc).creditAgentCommission(7L, 999L, 25L);
        verify(groupSvc).creditPayout(42L, 999L, 26L);
    }
}
