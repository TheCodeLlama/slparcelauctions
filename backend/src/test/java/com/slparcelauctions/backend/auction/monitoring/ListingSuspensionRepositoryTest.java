package com.slparcelauctions.backend.auction.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroup;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroupRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Repository test for {@link ListingSuspensionRepository}. Mirrors the
 * {@code @SpringBootTest} + {@code @ActiveProfiles("dev")} pattern used by the
 * other realty repository tests; this codebase does not use {@code @DataJpaTest}
 * because of Postgres-specific features in the schema (partial indexes, JSONB,
 * citext, etc.).
 *
 * <p>Spec: §8, §10, plan Task 3.
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
class ListingSuspensionRepositoryTest {

    @Autowired UserRepository userRepository;
    @Autowired RealtyGroupRepository realtyGroupRepository;
    @Autowired RealtyGroupSlGroupRepository realtyGroupSlGroupRepository;
    @Autowired AuctionRepository auctionRepository;
    @Autowired ListingSuspensionRepository repository;
    @Autowired DataSource dataSource;

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var stmt = conn.createStatement()) {
                stmt.execute("""
                    DELETE FROM listing_suspensions
                     WHERE auction_id IN (
                       SELECT id FROM auctions WHERE seller_id IN
                         (SELECT id FROM users WHERE email LIKE 'ls-rep-%@test.local'))
                    """);
                stmt.execute("""
                    DELETE FROM auction_parcel_snapshots
                     WHERE auction_id IN (
                       SELECT id FROM auctions WHERE seller_id IN
                         (SELECT id FROM users WHERE email LIKE 'ls-rep-%@test.local'))
                    """);
                stmt.execute("""
                    DELETE FROM auctions
                     WHERE seller_id IN (SELECT id FROM users WHERE email LIKE 'ls-rep-%@test.local')
                    """);
                stmt.execute("""
                    DELETE FROM realty_group_sl_groups
                     WHERE realty_group_id IN (
                       SELECT id FROM realty_groups WHERE leader_id IN
                         (SELECT id FROM users WHERE email LIKE 'ls-rep-%@test.local'))
                    """);
                stmt.execute("""
                    DELETE FROM realty_group_members
                     WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'ls-rep-%@test.local')
                    """);
                stmt.execute("""
                    DELETE FROM realty_groups
                     WHERE leader_id IN (SELECT id FROM users WHERE email LIKE 'ls-rep-%@test.local')
                    """);
                stmt.execute("DELETE FROM users WHERE email LIKE 'ls-rep-%@test.local'");
            }
        }
    }

    @Test
    void findByPublicId_returnsTheSavedRow() {
        Fixture f = newFixture();
        Auction a = persistAuctionForCase1(f, f.group.getId());
        ListingSuspension ls = repository.save(buildBulkSuspension(a, OffsetDateTime.now()));

        var found = repository.findByPublicId(ls.getPublicId());
        assertThat(found).isPresent();
        assertThat(found.get().getCause()).isEqualTo(ListingSuspensionCause.ADMIN_GROUP_BULK);
    }

    @Test
    void findByAuctionId_returnsAllRowsForAuction() {
        Fixture f = newFixture();
        Auction a = persistAuctionForCase1(f, f.group.getId());
        Auction b = persistAuctionForCase1(f, f.group.getId());

        repository.save(buildBulkSuspension(a, OffsetDateTime.now().minusHours(2)));
        repository.save(buildBulkSuspension(a, OffsetDateTime.now().minusHours(1)));
        repository.save(buildBulkSuspension(b, OffsetDateTime.now().minusHours(1)));

        assertThat(repository.findByAuctionId(a.getId())).hasSize(2);
        assertThat(repository.findByAuctionId(b.getId())).hasSize(1);
    }

    @Test
    void findExpiredBulkSuspends_returnsOnlyExpiredAdminGroupBulkRows() {
        Fixture f = newFixture();
        Auction a1 = persistAuctionForCase1(f, f.group.getId());
        Auction a2 = persistAuctionForCase1(f, f.group.getId());
        Auction a3 = persistAuctionForCase1(f, f.group.getId());
        Auction a4 = persistAuctionForCase1(f, f.group.getId());

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime threshold = now.minusHours(48);

        // old bulk suspension — expired
        ListingSuspension expired = repository.save(
                buildBulkSuspension(a1, now.minusHours(50)));

        // recent bulk suspension — not yet expired
        repository.save(buildBulkSuspension(a2, now.minusHours(10)));

        // old bulk suspension but already lifted — excluded
        ListingSuspension oldLifted = buildBulkSuspension(a3, now.minusHours(60));
        oldLifted.setLiftedAt(now.minusHours(30));
        repository.save(oldLifted);

        // old AUTO cause — excluded (cause != ADMIN_GROUP_BULK)
        ListingSuspension auto = ListingSuspension.builder()
            .auction(a4)
            .cause(ListingSuspensionCause.AUTO_OWNERSHIP_CHANGE)
            .suspendedAt(now.minusHours(60))
            .build();
        repository.save(auto);

        var due = repository.findExpiredBulkSuspends(threshold);
        assertThat(due).extracting(ListingSuspension::getId).containsExactly(expired.getId());
    }

    @Test
    void findActiveBulkSuspensionsForGroup_findsCase1AuctionsByDirectGroupId() {
        Fixture f = newFixture();
        Auction case1 = persistAuctionForCase1(f, f.group.getId());

        ListingSuspension ls = repository.save(
                buildBulkSuspension(case1, OffsetDateTime.now().minusHours(2)));

        var active = repository.findActiveBulkSuspensionsForGroup(f.group.getId());
        assertThat(active).extracting(ListingSuspension::getId).containsExactly(ls.getId());
    }

    @Test
    void findActiveBulkSuspensionsForGroup_findsCase3AuctionsViaSlGroup() {
        Fixture f = newFixture();
        RealtyGroupSlGroup slGroup = persistSlGroup(f.group);
        Auction case3 = persistAuctionForCase3(f, f.group.getId(), slGroup.getId());

        ListingSuspension ls = repository.save(
                buildBulkSuspension(case3, OffsetDateTime.now().minusHours(2)));

        var active = repository.findActiveBulkSuspensionsForGroup(f.group.getId());
        assertThat(active).extracting(ListingSuspension::getId).containsExactly(ls.getId());
    }

    @Test
    void findActiveBulkSuspensionsForGroup_excludesLiftedAndCancelledAndNonBulkRows() {
        Fixture f = newFixture();
        Auction a = persistAuctionForCase1(f, f.group.getId());
        Auction b = persistAuctionForCase1(f, f.group.getId());
        Auction c = persistAuctionForCase1(f, f.group.getId());
        Auction d = persistAuctionForCase1(f, f.group.getId());

        OffsetDateTime now = OffsetDateTime.now();

        // active bulk -> matches
        ListingSuspension active = repository.save(buildBulkSuspension(a, now.minusHours(1)));

        // lifted -> excluded
        ListingSuspension lifted = buildBulkSuspension(b, now.minusHours(2));
        lifted.setLiftedAt(now.minusMinutes(5));
        repository.save(lifted);

        // cancelled -> excluded
        ListingSuspension cancelled = buildBulkSuspension(c, now.minusHours(2));
        cancelled.setCancelledAt(now.minusMinutes(5));
        repository.save(cancelled);

        // admin-individual cause -> excluded
        ListingSuspension individual = ListingSuspension.builder()
            .auction(d)
            .cause(ListingSuspensionCause.ADMIN_INDIVIDUAL)
            .suspendedAt(now.minusHours(2))
            .build();
        repository.save(individual);

        var rows = repository.findActiveBulkSuspensionsForGroup(f.group.getId());
        assertThat(rows).extracting(ListingSuspension::getId).containsExactly(active.getId());
    }

    @Test
    void findActiveBulkSuspensionsForGroup_doesNotReturnOtherGroups() {
        Fixture f1 = newFixture();
        Fixture f2 = newFixture();

        Auction a1 = persistAuctionForCase1(f1, f1.group.getId());
        Auction a2 = persistAuctionForCase1(f2, f2.group.getId());

        repository.save(buildBulkSuspension(a1, OffsetDateTime.now().minusHours(2)));
        repository.save(buildBulkSuspension(a2, OffsetDateTime.now().minusHours(2)));

        var g1Rows = repository.findActiveBulkSuspensionsForGroup(f1.group.getId());
        assertThat(g1Rows).hasSize(1);
        assertThat(g1Rows.get(0).getAuction().getId()).isEqualTo(a1.getId());
    }

    // ─────────────────────── helpers ───────────────────────

    private record Fixture(User seller, RealtyGroup group) {}

    private Fixture newFixture() {
        User seller = userRepository.save(User.builder()
            .username("ls-rep-s-" + UUID.randomUUID().toString().substring(0, 6))
            .email("ls-rep-s-" + UUID.randomUUID() + "@test.local")
            .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
            .displayName("seller")
            .verified(true)
            .slAvatarUuid(UUID.randomUUID())
            .build());
        RealtyGroup group = realtyGroupRepository.save(RealtyGroup.builder()
            .name("LS Test " + suffix())
            .slug("ls-rep-" + UUID.randomUUID().toString().substring(0, 8))
            .leaderId(seller.getId())
            .build());
        return new Fixture(seller, group);
    }

    private RealtyGroupSlGroup persistSlGroup(RealtyGroup group) {
        return realtyGroupSlGroupRepository.save(RealtyGroupSlGroup.builder()
            .realtyGroupId(group.getId())
            .slGroupUuid(UUID.randomUUID())
            .slGroupName("SL Group " + suffix())
            .verified(true)
            .verifiedAt(OffsetDateTime.now())
            .build());
    }

    private Auction persistAuctionForCase1(Fixture f, Long realtyGroupId) {
        return persistAuction(f, realtyGroupId, null);
    }

    private Auction persistAuctionForCase3(Fixture f, Long realtyGroupId, Long slGroupId) {
        return persistAuction(f, realtyGroupId, slGroupId);
    }

    private Auction persistAuction(Fixture f, Long realtyGroupId, Long slGroupId) {
        UUID parcelUuid = UUID.randomUUID();
        Auction auction = auctionRepository.save(Auction.builder()
            .seller(f.seller)
            .slParcelUuid(parcelUuid)
            .title("Test " + suffix())
            .status(AuctionStatus.SUSPENDED)
            .verificationMethod(VerificationMethod.UUID_ENTRY)
            .verificationTier(VerificationTier.SCRIPT)
            .startingBid(1000L)
            .durationHours(168)
            .snipeProtect(false)
            .listingFeePaid(false)
            .currentBid(0L)
            .bidCount(0)
            .consecutiveWorldApiFailures(0)
            .commissionRate(new BigDecimal("0.05"))
            .agentFeeRate(BigDecimal.ZERO)
            .realtyGroupId(realtyGroupId)
            .realtyGroupSlGroupId(slGroupId)
            .endsAt(OffsetDateTime.now().plusHours(1))
            .build());
        auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
            .slParcelUuid(parcelUuid)
            .ownerUuid(f.seller.getSlAvatarUuid())
            .ownerType("agent")
            .parcelName("Test Parcel " + suffix())
            .regionName("Test Region")
            .regionMaturityRating("GENERAL")
            .areaSqm(1024)
            .positionX(128.0).positionY(64.0).positionZ(22.0)
            .build());
        return auctionRepository.save(auction);
    }

    private ListingSuspension buildBulkSuspension(Auction auction, OffsetDateTime suspendedAt) {
        return ListingSuspension.builder()
            .auction(auction)
            .cause(ListingSuspensionCause.ADMIN_GROUP_BULK)
            .bulkActionId(UUID.randomUUID())
            .reason("test bulk")
            .suspendedAt(suspendedAt)
            .build();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
