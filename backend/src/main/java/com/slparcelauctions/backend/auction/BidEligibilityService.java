package com.slparcelauctions.backend.auction;

import org.springframework.stereotype.Service;

import com.slparcelauctions.backend.auction.exception.GroupMemberCannotBidException;
import com.slparcelauctions.backend.auction.exception.SellerCannotBidException;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.user.User;

import lombok.RequiredArgsConstructor;

/**
 * COI gate for the bid path. Two rules today:
 * <ol>
 *   <li>Bidder == auction.seller → {@link SellerCannotBidException} (sibling to the inline
 *       check that existed before this service was introduced — moved here for consolidation).</li>
 *   <li>Bidder is a current member of the auction's realty group → {@link
 *       GroupMemberCannotBidException}.</li>
 * </ol>
 * Order matters: seller check is free (no DB hit) and the most common refusal in practice,
 * so it short-circuits before the member lookup.
 */
@Service
@RequiredArgsConstructor
public class BidEligibilityService {

    private final RealtyGroupMemberRepository memberRepo;

    public void assertCanBid(Auction auction, User bidder) {
        if (bidder.getId().equals(auction.getSeller().getId())) {
            throw new SellerCannotBidException();
        }
        Long groupId = auction.getRealtyGroupId();
        if (groupId != null
                && memberRepo.existsByGroupIdAndUserId(groupId, bidder.getId())) {
            throw new GroupMemberCannotBidException();
        }
    }
}
