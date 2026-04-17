package com.slparcelauctions.backend.auction.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.fraud.FraudFlag;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.user.User;

/**
 * Unit coverage for {@link SuspensionService}. Two public entry points
 * (ownership change, deleted parcel) tested separately; each must flip the
 * auction to {@link AuctionStatus#SUSPENDED}, stamp the ownership-check
 * timestamp, and persist a {@link FraudFlag} with the appropriate reason and
 * evidence payload.
 */
@ExtendWith(MockitoExtension.class)
class SuspensionServiceTest {

    private static final Long AUCTION_ID = 1L;
    private static final Long PARCEL_ID = 100L;
    private static final UUID PARCEL_UUID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SELLER_AVATAR = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID ATTACKER_AVATAR = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Mock AuctionRepository auctionRepo;
    @Mock FraudFlagRepository fraudFlagRepo;

    SuspensionService service;
    Clock fixed;

    @BeforeEach
    void setUp() {
        fixed = Clock.fixed(Instant.parse("2026-04-16T12:00:00Z"), ZoneOffset.UTC);
        service = new SuspensionService(auctionRepo, fraudFlagRepo, fixed);
        lenient().when(auctionRepo.save(any(Auction.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(fraudFlagRepo.save(any(FraudFlag.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void suspendForOwnershipChange_flipsStatus_stampsTimestamp_writesFlagWithEvidence() {
        Auction a = buildActive();
        ParcelMetadata attacker = new ParcelMetadata(
                PARCEL_UUID, ATTACKER_AVATAR, "agent",
                "Hijacked", "Coniston", 1024, "desc", null, "MATURE",
                1.0, 2.0, 3.0);

        service.suspendForOwnershipChange(a, attacker);

        assertThat(a.getStatus()).isEqualTo(AuctionStatus.SUSPENDED);
        assertThat(a.getLastOwnershipCheckAt()).isEqualTo(OffsetDateTime.now(fixed));
        verify(auctionRepo).save(a);

        ArgumentCaptor<FraudFlag> captor = ArgumentCaptor.forClass(FraudFlag.class);
        verify(fraudFlagRepo).save(captor.capture());
        FraudFlag flag = captor.getValue();
        assertThat(flag.getReason()).isEqualTo(FraudFlagReason.OWNERSHIP_CHANGED_TO_UNKNOWN);
        assertThat(flag.getAuction()).isSameAs(a);
        assertThat(flag.getParcel()).isSameAs(a.getParcel());
        assertThat(flag.getDetectedAt()).isEqualTo(OffsetDateTime.now(fixed));
        assertThat(flag.isResolved()).isFalse();
        assertThat(flag.getEvidenceJson())
                .containsEntry("expected_owner", SELLER_AVATAR.toString())
                .containsEntry("detected_owner", ATTACKER_AVATAR.toString())
                .containsEntry("detected_owner_type", "agent")
                .containsEntry("parcel_uuid", PARCEL_UUID.toString());
        // createdAt is populated by @CreationTimestamp on save — must NOT be
        // pre-set by SuspensionService or Hibernate will error out.
        assertThat(flag.getCreatedAt()).isNull();
    }

    @Test
    void suspendForDeletedParcel_flipsStatus_stampsTimestamp_writesFlagWithParcelOnly() {
        Auction a = buildActive();

        service.suspendForDeletedParcel(a);

        assertThat(a.getStatus()).isEqualTo(AuctionStatus.SUSPENDED);
        assertThat(a.getLastOwnershipCheckAt()).isEqualTo(OffsetDateTime.now(fixed));
        verify(auctionRepo).save(a);

        ArgumentCaptor<FraudFlag> captor = ArgumentCaptor.forClass(FraudFlag.class);
        verify(fraudFlagRepo).save(captor.capture());
        FraudFlag flag = captor.getValue();
        assertThat(flag.getReason()).isEqualTo(FraudFlagReason.PARCEL_DELETED_OR_MERGED);
        assertThat(flag.getDetectedAt()).isEqualTo(OffsetDateTime.now(fixed));
        assertThat(flag.getEvidenceJson())
                .containsEntry("parcel_uuid", PARCEL_UUID.toString());
    }

    private Auction buildActive() {
        User seller = User.builder().id(42L).email("s@example.com")
                .slAvatarUuid(SELLER_AVATAR).verified(true).build();
        Parcel parcel = Parcel.builder().id(PARCEL_ID).slParcelUuid(PARCEL_UUID)
                .ownerUuid(SELLER_AVATAR).ownerType("agent")
                .regionName("Coniston").continentName("Sansara").verified(true).build();
        return Auction.builder()
                .id(AUCTION_ID).seller(seller).parcel(parcel)
                .status(AuctionStatus.ACTIVE)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .startingBid(1000L).durationHours(168)
                .snipeProtect(false).listingFeePaid(true)
                .currentBid(0L).bidCount(0)
                .consecutiveWorldApiFailures(0)
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .tags(new HashSet<>())
                .build();
    }
}
