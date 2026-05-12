package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroup;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroupRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Persistence-level verification of the realty-group repository methods:
 * <ul>
 *   <li>{@link AuctionRepository#existsActiveListingsByGroupId(Long)}</li>
 *   <li>{@link AuctionRepository#reassignListingAgentToLeaderForCase1(Long, Long, Long)}</li>
 *   <li>{@link AuctionRepository#reassignSellerToLeaderForCase3(Long, Long, Long)}</li>
 * </ul>
 *
 * <p>Uses {@code @Transactional} so each test auto-rolls back with no manual cleanup.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.auction-end.enabled=false",
        "slpa.ownership-monitor.enabled=false",
        "slpa.escrow.ownership-monitor-job.enabled=false",
        "slpa.escrow.timeout-job.enabled=false",
        "slpa.escrow.command-dispatcher-job.enabled=false",
        "slpa.review.scheduler.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false",
        "slpa.realty.invitation-expiry.enabled=false"
})
@Transactional
class AuctionRepositoryRealtyTest {

    @Autowired AuctionRepository auctionRepo;
    @Autowired RealtyGroupRepository groupRepo;
    @Autowired UserRepository userRepo;
    @Autowired RealtyGroupSlGroupRepository slGroupRepo;

    // ── existsActiveListingsByGroupId ──────────────────────────────────────

    @Test
    void existsActiveListingsByGroupId_true_when_active() {
        User seller = userRepo.save(buildUser("active-seller"));
        RealtyGroup g = groupRepo.save(buildGroup("Active Group", "active-group", seller.getId()));

        auctionRepo.save(buildAuction(seller, g.getId(), AuctionStatus.ACTIVE));

        assertThat(auctionRepo.existsActiveListingsByGroupId(g.getId())).isTrue();
    }

    @Test
    void existsActiveListingsByGroupId_false_when_only_ended() {
        User seller = userRepo.save(buildUser("ended-seller"));
        RealtyGroup g = groupRepo.save(buildGroup("Ended Group", "ended-group", seller.getId()));

        auctionRepo.save(buildAuction(seller, g.getId(), AuctionStatus.ENDED));

        assertThat(auctionRepo.existsActiveListingsByGroupId(g.getId())).isFalse();
    }

    @Test
    void existsActiveListingsByGroupId_true_for_draft_and_verification_pending() {
        User seller = userRepo.save(buildUser("draft-seller"));
        RealtyGroup g = groupRepo.save(buildGroup("Draft Group", "draft-group", seller.getId()));

        auctionRepo.save(buildAuction(seller, g.getId(), AuctionStatus.DRAFT));
        auctionRepo.save(buildAuction(seller, g.getId(), AuctionStatus.VERIFICATION_PENDING));

        assertThat(auctionRepo.existsActiveListingsByGroupId(g.getId())).isTrue();
    }

    // ── reassignListingAgentToLeaderForCase1 ───────────────────────────────

    @Test
    void reassignListingAgentToLeaderForCase1_updates_active_only_and_skips_case3() {
        User leader = userRepo.save(buildUser("reassign-leader"));
        User agent  = userRepo.save(buildUser("reassign-agent"));
        RealtyGroup g = groupRepo.save(buildGroup("Reassign Group", "reassign-group", leader.getId()));

        // Case-1 legacy: realty_group_id set, realty_group_sl_group_id NULL.
        Auction case1Active = buildAuction(agent, g.getId(), AuctionStatus.ACTIVE);
        case1Active.setListingAgent(agent);
        case1Active = auctionRepo.save(case1Active);

        // Case-1 ENDED — outside the pre-terminal status set.
        Auction case1Ended = buildAuction(agent, g.getId(), AuctionStatus.ENDED);
        case1Ended.setListingAgent(agent);
        case1Ended = auctionRepo.save(case1Ended);

        // Case-3: realty_group_sl_group_id set. Must NOT have listing_agent_id reassigned
        // by the case-1 query.
        RealtyGroupSlGroup slGroup = slGroupRepo.save(buildSlGroup(g.getId()));
        Auction case3Active = buildAuction(agent, g.getId(), AuctionStatus.ACTIVE);
        case3Active.setListingAgent(agent);
        case3Active.setRealtyGroupSlGroupId(slGroup.getId());
        case3Active = auctionRepo.save(case3Active);

        int rowsUpdated = auctionRepo.reassignListingAgentToLeaderForCase1(
                agent.getId(), g.getId(), leader.getId());

        assertThat(rowsUpdated).isEqualTo(1);

        // Case-1 ACTIVE flipped.
        Auction updatedCase1 = auctionRepo.findById(case1Active.getId()).orElseThrow();
        assertThat(updatedCase1.getListingAgent().getId()).isEqualTo(leader.getId());

        // Case-1 ENDED untouched.
        Auction untouchedEnded = auctionRepo.findById(case1Ended.getId()).orElseThrow();
        assertThat(untouchedEnded.getListingAgent().getId()).isEqualTo(agent.getId());

        // Case-3 untouched by the case-1 query — listing_agent_id stable.
        Auction untouchedCase3 = auctionRepo.findById(case3Active.getId()).orElseThrow();
        assertThat(untouchedCase3.getListingAgent().getId()).isEqualTo(agent.getId());
    }

    // ── reassignSellerToLeaderForCase3 ─────────────────────────────────────

    @Test
    void reassignSellerToLeaderForCase3_updates_active_only_and_skips_case1() {
        User leader = userRepo.save(buildUser("case3-leader"));
        User member = userRepo.save(buildUser("case3-member"));
        RealtyGroup g = groupRepo.save(buildGroup("Case3 Group", "case3-group", leader.getId()));

        RealtyGroupSlGroup slGroup = slGroupRepo.save(buildSlGroup(g.getId()));

        // Case-3 ACTIVE: seller is the departing member, realty_group_sl_group_id set.
        Auction case3Active = buildAuction(member, g.getId(), AuctionStatus.ACTIVE);
        case3Active.setListingAgent(member);
        case3Active.setRealtyGroupSlGroupId(slGroup.getId());
        case3Active = auctionRepo.save(case3Active);

        // Case-3 ENDED — outside the pre-terminal status set.
        Auction case3Ended = buildAuction(member, g.getId(), AuctionStatus.ENDED);
        case3Ended.setListingAgent(member);
        case3Ended.setRealtyGroupSlGroupId(slGroup.getId());
        case3Ended = auctionRepo.save(case3Ended);

        // Case-1 legacy: realty_group_sl_group_id NULL. Must NOT have seller_id reassigned.
        Auction case1Active = buildAuction(member, g.getId(), AuctionStatus.ACTIVE);
        case1Active.setListingAgent(member);
        case1Active = auctionRepo.save(case1Active);

        int rowsUpdated = auctionRepo.reassignSellerToLeaderForCase3(
                member.getId(), g.getId(), leader.getId());

        assertThat(rowsUpdated).isEqualTo(1);

        // Case-3 ACTIVE: seller_id flipped to leader; listing_agent_id stays the member
        // (commission attribution preserved).
        Auction updatedCase3 = auctionRepo.findById(case3Active.getId()).orElseThrow();
        assertThat(updatedCase3.getSeller().getId()).isEqualTo(leader.getId());
        assertThat(updatedCase3.getListingAgent().getId()).isEqualTo(member.getId());

        // Case-3 ENDED untouched.
        Auction untouchedEnded = auctionRepo.findById(case3Ended.getId()).orElseThrow();
        assertThat(untouchedEnded.getSeller().getId()).isEqualTo(member.getId());

        // Case-1 untouched by the case-3 query — seller_id stable.
        Auction untouchedCase1 = auctionRepo.findById(case1Active.getId()).orElseThrow();
        assertThat(untouchedCase1.getSeller().getId()).isEqualTo(member.getId());
    }

    @Test
    void reassignQueries_skip_individual_auctions_without_realty_group() {
        // Individual auction has realty_group_id NULL — neither query's WHERE clause
        // matches, so both leave it untouched even when the departing user is its seller
        // and listing agent.
        User leader = userRepo.save(buildUser("indiv-leader"));
        User member = userRepo.save(buildUser("indiv-member"));
        RealtyGroup g = groupRepo.save(buildGroup("Indiv Group", "indiv-group", leader.getId()));

        Auction individual = buildAuction(member, null, AuctionStatus.ACTIVE);
        individual.setListingAgent(member);
        individual = auctionRepo.save(individual);

        int case3Rows = auctionRepo.reassignSellerToLeaderForCase3(
                member.getId(), g.getId(), leader.getId());
        int case1Rows = auctionRepo.reassignListingAgentToLeaderForCase1(
                member.getId(), g.getId(), leader.getId());

        assertThat(case3Rows).isEqualTo(0);
        assertThat(case1Rows).isEqualTo(0);

        Auction reloaded = auctionRepo.findById(individual.getId()).orElseThrow();
        assertThat(reloaded.getSeller().getId()).isEqualTo(member.getId());
        assertThat(reloaded.getListingAgent().getId()).isEqualTo(member.getId());
    }

    // ── builders ──────────────────────────────────────────────────────────

    private static User buildUser(String tag) {
        return User.builder()
                .username("u-" + tag + "-" + UUID.randomUUID().toString().substring(0, 6))
                .email(tag + "-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .verified(true)
                .slAvatarUuid(UUID.randomUUID())
                .build();
    }

    private static RealtyGroup buildGroup(String name, String slug, Long leaderId) {
        return RealtyGroup.builder()
                .name(name)
                .slug(slug + "-" + UUID.randomUUID().toString().substring(0, 6))
                .leaderId(leaderId)
                .agentFeeRate(new BigDecimal("0.0200"))
                .agentFeeSplit(new BigDecimal("0.5000"))
                .build();
    }

    private static RealtyGroupSlGroup buildSlGroup(Long realtyGroupId) {
        return RealtyGroupSlGroup.builder()
                .realtyGroupId(realtyGroupId)
                .slGroupUuid(UUID.randomUUID())
                .slGroupName("Test SL Group")
                .verified(true)
                .verifiedAt(OffsetDateTime.now())
                .build();
    }

    private static Auction buildAuction(User seller, Long realtyGroupId, AuctionStatus status) {
        UUID parcelUuid = UUID.randomUUID();
        Auction auction = Auction.builder()
                .seller(seller)
                .slParcelUuid(parcelUuid)
                .realtyGroupId(realtyGroupId)
                .status(status)
                .title("Test Auction " + UUID.randomUUID().toString().substring(0, 6))
                .startingBid(1_000L)
                .durationHours(24)
                .snipeProtect(false)
                .endsAt(OffsetDateTime.now().plusDays(1))
                .listingFeePaid(false)
                .currentBid(0L)
                .bidCount(0)
                .consecutiveWorldApiFailures(0)
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(new BigDecimal("0.0200"))
                .build();
        auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid)
                .ownerUuid(seller.getSlAvatarUuid())
                .ownerType("agent")
                .parcelName("Test Parcel")
                .regionName("Test Region")
                .regionMaturityRating("GENERAL")
                .areaSqm(1024)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
        return auction;
    }
}
