package com.slparcelauctions.backend.auction.agentfee;

import java.lang.reflect.Field;
import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupWalletService;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.wallet.WalletService;

import static org.mockito.Mockito.*;

class AgentFeeDistributorTest {

    private final RealtyGroupWalletService groupSvc = mock(RealtyGroupWalletService.class);
    private final WalletService userSvc = mock(WalletService.class);
    private final AgentFeeDistributor dist = new AgentFeeDistributor(groupSvc, userSvc);

    /** Build a User with a specific id set via reflection. */
    private static User userWithId(long id) throws Exception {
        User u = User.builder().username("agent").passwordHash("x").build();
        Field idField = com.slparcelauctions.backend.common.BaseEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(u, id);
        return u;
    }

    @Test
    void splitsAgentFeeBetweenGroupAndAgentByFloorOfSplit() throws Exception {
        Auction a = mock(Auction.class);
        when(a.getRealtyGroupId()).thenReturn(42L);
        when(a.getAgentFeeSplit()).thenReturn(new BigDecimal("0.5"));
        when(a.getId()).thenReturn(999L);
        User agent = userWithId(7L);
        when(a.getListingAgent()).thenReturn(agent);

        // 50 * 0.5 = 25 group, 25 agent
        dist.distribute(a, 50L);

        verify(groupSvc).creditAgentFee(42L, 999L, 25L);
        verify(userSvc).creditAgentFee(7L, 999L, 25L);
    }

    @Test
    void floorRoundingOnOddSplit() throws Exception {
        Auction a = mock(Auction.class);
        when(a.getRealtyGroupId()).thenReturn(42L);
        when(a.getAgentFeeSplit()).thenReturn(new BigDecimal("0.5"));
        when(a.getId()).thenReturn(999L);
        User agent = userWithId(7L);
        when(a.getListingAgent()).thenReturn(agent);

        // 51 * 0.5 = 25.5, floor -> 25 group, 26 agent
        dist.distribute(a, 51L);

        verify(groupSvc).creditAgentFee(42L, 999L, 25L);
        verify(userSvc).creditAgentFee(7L, 999L, 26L);
    }

    @Test
    void zeroSplitMeansAgentGetsEverything() throws Exception {
        Auction a = mock(Auction.class);
        when(a.getRealtyGroupId()).thenReturn(42L);
        when(a.getAgentFeeSplit()).thenReturn(BigDecimal.ZERO);
        when(a.getId()).thenReturn(999L);
        User agent = userWithId(7L);
        when(a.getListingAgent()).thenReturn(agent);

        dist.distribute(a, 100L);

        verify(groupSvc, never()).creditAgentFee(anyLong(), anyLong(), anyLong());
        verify(userSvc).creditAgentFee(7L, 999L, 100L);
    }

    @Test
    void fullSplitMeansGroupGetsEverything() throws Exception {
        Auction a = mock(Auction.class);
        when(a.getRealtyGroupId()).thenReturn(42L);
        when(a.getAgentFeeSplit()).thenReturn(new BigDecimal("1.0"));
        when(a.getId()).thenReturn(999L);
        User agent = userWithId(7L);
        when(a.getListingAgent()).thenReturn(agent);

        dist.distribute(a, 100L);

        verify(groupSvc).creditAgentFee(42L, 999L, 100L);
        verify(userSvc, never()).creditAgentFee(anyLong(), anyLong(), anyLong());
    }

    @Test
    void nullGroupIdSkipsGroupCredit() throws Exception {
        Auction a = mock(Auction.class);
        when(a.getRealtyGroupId()).thenReturn(null);
        when(a.getAgentFeeSplit()).thenReturn(new BigDecimal("0.5"));
        when(a.getId()).thenReturn(999L);
        User agent = userWithId(7L);
        when(a.getListingAgent()).thenReturn(agent);

        dist.distribute(a, 50L);

        verify(groupSvc, never()).creditAgentFee(anyLong(), anyLong(), anyLong());
        verify(userSvc).creditAgentFee(7L, 999L, 25L);
    }

    @Test
    void nullAgentSkipsAgentCredit() {
        Auction a = mock(Auction.class);
        when(a.getRealtyGroupId()).thenReturn(42L);
        when(a.getAgentFeeSplit()).thenReturn(new BigDecimal("0.5"));
        when(a.getId()).thenReturn(999L);
        when(a.getListingAgent()).thenReturn(null);

        dist.distribute(a, 50L);

        verify(groupSvc).creditAgentFee(42L, 999L, 25L);
        verify(userSvc, never()).creditAgentFee(anyLong(), anyLong(), anyLong());
    }
}
