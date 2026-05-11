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
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Persistence-level verification of the two realty-group repository methods:
 * <ul>
 *   <li>{@link AuctionRepository#existsActiveListingsByGroupId(Long)}</li>
 *   <li>{@link AuctionRepository#reassignListingAgentForGroup(Long, Long, Long)}</li>
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

    // ── reassignListingAgentForGroup ───────────────────────────────────────

    @Test
    void reassignListingAgentForGroup_updates_active_only() {
        User leader = userRepo.save(buildUser("reassign-leader"));
        User agent  = userRepo.save(buildUser("reassign-agent"));
        RealtyGroup g = groupRepo.save(buildGroup("Reassign Group", "reassign-group", leader.getId()));

        Auction active = buildAuction(agent, g.getId(), AuctionStatus.ACTIVE);
        active.setListingAgent(agent);
        active = auctionRepo.save(active);

        Auction ended = buildAuction(agent, g.getId(), AuctionStatus.ENDED);
        ended.setListingAgent(agent);
        ended = auctionRepo.save(ended);

        int rowsUpdated = auctionRepo.reassignListingAgentForGroup(
                g.getId(), agent.getId(), leader.getId());

        assertThat(rowsUpdated).isEqualTo(1);

        // Active auction's listing agent must now be the leader.
        Auction updatedActive = auctionRepo.findById(active.getId()).orElseThrow();
        assertThat(updatedActive.getListingAgent().getId()).isEqualTo(leader.getId());

        // Ended auction must be untouched — still points at the original agent.
        Auction untouchedEnded = auctionRepo.findById(ended.getId()).orElseThrow();
        assertThat(untouchedEnded.getListingAgent().getId()).isEqualTo(agent.getId());
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
