package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.auction.exception.GroupMemberCannotBidException;
import com.slparcelauctions.backend.auction.exception.SellerCannotBidException;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.user.User;

@ExtendWith(MockitoExtension.class)
class BidEligibilityServiceTest {

    @Mock RealtyGroupMemberRepository members;
    @InjectMocks BidEligibilityService service;

    private static User user(long id) {
        return User.builder().id(id).build();
    }

    private static Auction individualAuction(long sellerId) {
        return Auction.builder()
                .seller(user(sellerId))
                .realtyGroupId(null)
                .build();
    }

    private static Auction groupAuction(long sellerId, long groupId) {
        return Auction.builder()
                .seller(user(sellerId))
                .realtyGroupId(groupId)
                .build();
    }

    @Test
    void seller_cannot_bid_on_own_auction() {
        Auction a = individualAuction(1L);
        assertThatThrownBy(() -> service.assertCanBid(a, user(1L)))
                .isInstanceOf(SellerCannotBidException.class);
    }

    @Test
    void unrelated_user_can_bid_on_individual_auction() {
        Auction a = individualAuction(1L);
        assertThatCode(() -> service.assertCanBid(a, user(2L))).doesNotThrowAnyException();
    }

    @Test
    void group_member_cannot_bid_on_group_auction() {
        Auction a = groupAuction(1L, 99L);
        when(members.existsByGroupIdAndUserId(99L, 2L)).thenReturn(true);
        assertThatThrownBy(() -> service.assertCanBid(a, user(2L)))
                .isInstanceOf(GroupMemberCannotBidException.class);
    }

    @Test
    void non_member_can_bid_on_group_auction() {
        Auction a = groupAuction(1L, 99L);
        when(members.existsByGroupIdAndUserId(99L, 2L)).thenReturn(false);
        assertThatCode(() -> service.assertCanBid(a, user(2L))).doesNotThrowAnyException();
    }

    @Test
    void seller_check_short_circuits_before_db_lookup() {
        Auction a = groupAuction(1L, 99L);
        assertThatThrownBy(() -> service.assertCanBid(a, user(1L)))
                .isInstanceOf(SellerCannotBidException.class);
        Mockito.verify(members, Mockito.never())
                .existsByGroupIdAndUserId(anyLong(), anyLong());
    }
}
