package com.slparcelauctions.backend.common;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@link BaseMutableEntity#version} drives optimistic locking:
 * a stale detached copy raises {@link OptimisticLockException} (or Spring's
 * wrapper) when flushed after a concurrent winner has already incremented the
 * version column.
 *
 * <p>This test is NOT {@code @Transactional}: optimistic-lock conflicts only
 * surface when two separate transactions flush against the same row. An ambient
 * transaction wrapper would serialise both flush calls into a single session and
 * mask the conflict.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
class BaseMutableEntityVersionTest {

    @Autowired AuctionRepository auctionRepository;
    @Autowired UserRepository userRepository;
    @Autowired PlatformTransactionManager txManager;

    private final AtomicLong savedAuctionId = new AtomicLong(-1);
    private final AtomicLong savedSellerId = new AtomicLong(-1);

    @AfterEach
    void cleanup() {
        long auctionId = savedAuctionId.get();
        long sellerId = savedSellerId.get();
        if (auctionId > 0) {
            auctionRepository.deleteById(auctionId);
        }
        if (sellerId > 0) {
            userRepository.deleteById(sellerId);
        }
    }

    @Test
    void concurrentUpdateRaisesOptimisticLockException() {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        // TX1: persist the auction so it gets a committed version=0 row.
        AtomicReference<Auction> staleRef = new AtomicReference<>();
        tx.executeWithoutResult(status -> {
            User seller = userRepository.save(newUser("opt-lock-seller"));
            savedSellerId.set(seller.getId());

            UUID parcelUuid = UUID.randomUUID();
            Auction a = Auction.builder()
                    .title("optimistic lock test")
                    .slParcelUuid(parcelUuid)
                    .seller(seller)
                    .status(AuctionStatus.DRAFT)
                    .verificationMethod(VerificationMethod.UUID_ENTRY)
                    .verificationTier(VerificationTier.SCRIPT)
                    .startingBid(10L)
                    .durationHours(24)
                    .snipeProtect(false)
                    .listingFeePaid(false)
                    .currentBid(0L)
                    .bidCount(0)
                    .consecutiveWorldApiFailures(0)
                    .commissionRate(new BigDecimal("0.05"))
                    .agentFeeRate(BigDecimal.ZERO)
                    .build();
            a.setParcelSnapshot(AuctionParcelSnapshot.builder()
                    .slParcelUuid(parcelUuid)
                    .ownerUuid(UUID.randomUUID())
                    .ownerType("agent")
                    .parcelName("Opt Lock Parcel")
                    .regionName("Test Region")
                    .regionMaturityRating("GENERAL")
                    .areaSqm(512)
                    .positionX(128.0).positionY(64.0).positionZ(22.0)
                    .build());
            Auction saved = auctionRepository.save(a);
            savedAuctionId.set(saved.getId());
            staleRef.set(saved);     // stale copy: version=0, will be detached after this tx
        });

        Auction stale = staleRef.get();          // detached, version=0

        // TX2: "winning" update — loads fresh (version=0) and flushes (version becomes 1 in DB).
        tx.executeWithoutResult(status -> {
            Auction winner = auctionRepository.findById(savedAuctionId.get()).orElseThrow();
            winner.setTitle("updated by winner");
            auctionRepository.saveAndFlush(winner);
        });

        // TX3: attempt to flush the stale copy — version=0 vs DB version=1 → must raise.
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            stale.setTitle("updated by stale");
            auctionRepository.saveAndFlush(stale);
        })).isInstanceOfAny(
                OptimisticLockException.class,
                ObjectOptimisticLockingFailureException.class);
    }

    private static User newUser(String label) {
        return User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email(label + "-" + UUID.randomUUID() + "@example.com")
                .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                .displayName(label)
                .verified(true)
                .slAvatarUuid(UUID.randomUUID())
                .build();
    }
}
