package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Persistence-level verification of
 * {@link AuctionRepository#findDueForOwnershipCheck}. Confirms that:
 * <ul>
 *   <li>ACTIVE auctions with {@code lastOwnershipCheckAt <= cutoff} are
 *       returned.</li>
 *   <li>ACTIVE auctions with null {@code lastOwnershipCheckAt} are returned
 *       (NULLS FIRST in the ORDER BY).</li>
 *   <li>Non-ACTIVE auctions are excluded even if their timestamp is stale.</li>
 *   <li>Results are ordered oldest-first (null → earliest timestamp → later).</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false"
})
@Transactional
class AuctionRepositoryOwnershipCheckTest {

    @Autowired AuctionRepository auctionRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired UserRepository userRepo;

    @Test
    void findDueForOwnershipCheck_returnsStaleAndNullOrderedOldestFirst_excludesNonActive() {
        User seller = userRepo.save(User.builder()
                .email("due-seller-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Due Seller")
                .verified(true)
                .slAvatarUuid(UUID.randomUUID())
                .build());

        // Each row gets its own parcel so the parcel-lock partial unique
        // index (which covers ACTIVE and ENDED, among others) doesn't reject
        // duplicate rows on the same parcel_id.
        Parcel p1 = parcelRepo.save(buildParcel());
        Parcel p2 = parcelRepo.save(buildParcel());
        Parcel p3 = parcelRepo.save(buildParcel());
        Parcel p4 = parcelRepo.save(buildParcel());
        Parcel p5 = parcelRepo.save(buildParcel());
        Parcel p6 = parcelRepo.save(buildParcel());

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime cutoff = now.minusMinutes(30);

        // 1) Stale (at cutoff boundary — should be included via <=)
        Auction stale = auctionRepo.save(build(seller, p1, AuctionStatus.ACTIVE, cutoff));
        // 2) Very stale (older — should also be included)
        Auction veryStale = auctionRepo.save(build(seller, p2, AuctionStatus.ACTIVE, now.minusHours(3)));
        // 3) Never checked (null timestamp — NULLS FIRST)
        Auction neverChecked = auctionRepo.save(build(seller, p3, AuctionStatus.ACTIVE, null));
        // 4) Fresh (after cutoff — excluded)
        Auction fresh = auctionRepo.save(build(seller, p4, AuctionStatus.ACTIVE, now.minusMinutes(5)));
        // 5) SUSPENDED (excluded regardless of timestamp)
        Auction suspended = auctionRepo.save(build(seller, p5, AuctionStatus.SUSPENDED, now.minusHours(5)));
        // 6) ENDED (excluded regardless of timestamp)
        Auction ended = auctionRepo.save(build(seller, p6, AuctionStatus.ENDED, now.minusHours(5)));

        List<Long> due = auctionRepo.findDueForOwnershipCheck(cutoff, now);

        assertThat(due).containsExactlyInAnyOrder(
                stale.getId(), veryStale.getId(), neverChecked.getId());
        assertThat(due).doesNotContain(fresh.getId(), suspended.getId(), ended.getId());

        // Ordering: null (NULLS FIRST), then oldest timestamp, then newer.
        assertThat(due).containsExactly(
                neverChecked.getId(), veryStale.getId(), stale.getId());
    }

    @Test
    void findDueForOwnershipCheck_includesPostCancelWatched_excludesExpired() {
        // Epic 08 sub-spec 2 §6 — a CANCELLED auction with an open watch
        // window should be returned alongside ACTIVE due rows; one whose
        // watch window has expired must NOT.
        User seller = userRepo.save(User.builder()
                .email("watch-seller-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName("Watch Seller")
                .verified(true)
                .slAvatarUuid(UUID.randomUUID())
                .build());

        Parcel pa = parcelRepo.save(buildParcel());
        Parcel pb = parcelRepo.save(buildParcel());
        Parcel pc = parcelRepo.save(buildParcel());

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime cutoff = now.minusMinutes(30);

        // ACTIVE, due — should be picked up.
        Auction active = auctionRepo.save(buildWithWatch(seller, pa,
                AuctionStatus.ACTIVE, now.minusHours(1), null));
        // CANCELLED with open post-cancel watch — should be picked up.
        Auction watched = buildWithWatch(seller, pb,
                AuctionStatus.CANCELLED, now.minusHours(1), now.plusHours(24));
        watched = auctionRepo.save(watched);
        // CANCELLED with expired post-cancel watch — must be excluded even
        // though lastOwnershipCheckAt is stale.
        Auction expired = buildWithWatch(seller, pc,
                AuctionStatus.CANCELLED, now.minusHours(1), now.minusHours(1));
        expired = auctionRepo.save(expired);

        List<Long> due = auctionRepo.findDueForOwnershipCheck(cutoff, now);

        assertThat(due).contains(active.getId(), watched.getId());
        assertThat(due).doesNotContain(expired.getId());
    }

    private Auction buildWithWatch(User seller, Parcel parcel, AuctionStatus status,
                                   OffsetDateTime lastCheck, OffsetDateTime watchUntil) {
        Auction a = build(seller, parcel, status, lastCheck);
        a.setPostCancelWatchUntil(watchUntil);
        return a;
    }

    private Parcel buildParcel() {
        return Parcel.builder()
                .slParcelUuid(UUID.randomUUID())
                .ownerUuid(UUID.randomUUID())
                .ownerType("agent")
                .regionName("Coniston")
                .continentName("Sansara")
                .areaSqm(1024)
                .maturityRating("MODERATE")
                .verified(true)
                .verifiedAt(OffsetDateTime.now())
                .build();
    }

    private Auction build(User seller, Parcel parcel, AuctionStatus status, OffsetDateTime lastCheck) {
        return Auction.builder()
                .title("Test listing")
                .parcel(parcel)
                .seller(seller)
                .status(status)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .verificationTier(VerificationTier.SCRIPT)
                .startingBid(1000L)
                .durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(true)
                .currentBid(0L)
                .bidCount(0)
                .consecutiveWorldApiFailures(0)
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .lastOwnershipCheckAt(lastCheck)
                .build();
    }
}
