package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.auction.dto.PendingVerification;
import com.slparcelauctions.backend.bot.BotTaskRepository;
import com.slparcelauctions.backend.bot.BotTaskService;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.verification.VerificationCodeService;
import com.slparcelauctions.backend.verification.dto.ActiveCodeResponse;
import com.slparcelauctions.backend.verification.dto.GenerateCodeResponse;

/**
 * Unit coverage for {@link AuctionVerificationService} Method B (REZZABLE).
 *
 * <p>Method B is asynchronous: {@code triggerVerification} generates a PARCEL
 * code, leaves the auction in {@code VERIFICATION_PENDING}, and returns. The
 * LSL callback at {@code /api/v1/sl/parcel/verify} drives the transition to
 * ACTIVE (covered by {@code SlParcelVerifyServiceTest} +
 * {@code SlParcelVerifyControllerIntegrationTest}).
 */
@ExtendWith(MockitoExtension.class)
class AuctionVerificationServiceMethodBTest {

    private static final Long SELLER_ID = 42L;
    private static final Long AUCTION_ID = 1L;
    private static final Long PARCEL_ID = 100L;
    private static final UUID PARCEL_UUID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SELLER_AVATAR = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final UUID ESCROW_UUID = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final long SENTINEL_PRICE = 999999999L;

    @Mock AuctionService auctionService;
    @Mock AuctionRepository auctionRepo;
    @Mock SlWorldApiClient worldApi;
    @Mock VerificationCodeService verificationCodeService;
    @Mock BotTaskService botTaskService;
    @Mock BotTaskRepository botTaskRepo;

    AuctionVerificationService service;

    private User seller;
    private Parcel parcel;
    private Clock fixed;

    @BeforeEach
    void setUp() {
        fixed = Clock.fixed(Instant.parse("2026-04-16T12:00:00Z"), ZoneOffset.UTC);
        service = new AuctionVerificationService(
                auctionService, auctionRepo, worldApi, verificationCodeService,
                botTaskService, botTaskRepo, fixed, ESCROW_UUID, SENTINEL_PRICE);

        seller = User.builder().id(SELLER_ID).email("s@example.com")
                .slAvatarUuid(SELLER_AVATAR).verified(true).build();
        parcel = Parcel.builder().id(PARCEL_ID).slParcelUuid(PARCEL_UUID)
                .ownerUuid(SELLER_AVATAR).ownerType("agent")
                .regionName("Coniston").continentName("Sansara").verified(true).build();

        lenient().when(auctionRepo.save(any(Auction.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // -------------------------------------------------------------------------
    // triggerVerification — generates code + stays pending
    // -------------------------------------------------------------------------

    @Test
    void verify_fromDraftPaid_withRezzable_staysPendingAndGeneratesCode() {
        Auction a = build(AuctionStatus.DRAFT_PAID);
        when(auctionService.loadForSeller(AUCTION_ID, SELLER_ID)).thenReturn(a);
        when(verificationCodeService.generateForParcel(SELLER_ID, AUCTION_ID))
                .thenReturn(new GenerateCodeResponse("123456",
                        OffsetDateTime.now(fixed).plusMinutes(15)));

        Auction out = service.triggerVerification(AUCTION_ID, SELLER_ID);

        assertThat(out.getStatus()).isEqualTo(AuctionStatus.VERIFICATION_PENDING);
        // No transition to ACTIVE — LSL callback will drive that.
        assertThat(out.getStartsAt()).isNull();
        assertThat(out.getEndsAt()).isNull();
        assertThat(out.getVerifiedAt()).isNull();
        verify(verificationCodeService).generateForParcel(SELLER_ID, AUCTION_ID);
    }

    @Test
    void verify_fromVerificationFailed_retry_generatesNewCode() {
        // On retry, VerificationCodeService.generateForParcel is responsible for
        // voiding any prior PARCEL codes (covered in VerificationCodeServiceTest);
        // here we assert that dispatch still calls it from the retry source status
        // and that the stale verificationNotes is cleared before the code is issued.
        Auction a = build(AuctionStatus.VERIFICATION_FAILED);
        a.setVerificationNotes("stale failure note");
        when(auctionService.loadForSeller(AUCTION_ID, SELLER_ID)).thenReturn(a);
        when(verificationCodeService.generateForParcel(SELLER_ID, AUCTION_ID))
                .thenReturn(new GenerateCodeResponse("654321",
                        OffsetDateTime.now(fixed).plusMinutes(15)));

        Auction out = service.triggerVerification(AUCTION_ID, SELLER_ID);

        assertThat(out.getStatus()).isEqualTo(AuctionStatus.VERIFICATION_PENDING);
        assertThat(out.getVerificationNotes()).isNull();
        verify(verificationCodeService).generateForParcel(SELLER_ID, AUCTION_ID);
    }

    // -------------------------------------------------------------------------
    // buildPendingVerification — REZZABLE
    // -------------------------------------------------------------------------

    @Test
    void buildPendingVerification_rezzablePending_returnsCodeAndExpiry() {
        Auction a = build(AuctionStatus.VERIFICATION_PENDING);
        OffsetDateTime expires = OffsetDateTime.now(fixed).plusMinutes(15);
        when(verificationCodeService.findActiveForParcel(SELLER_ID, AUCTION_ID))
                .thenReturn(Optional.of(new ActiveCodeResponse("987654", expires)));

        PendingVerification pv = service.buildPendingVerification(a);

        assertThat(pv).isNotNull();
        assertThat(pv.method()).isEqualTo(VerificationMethod.REZZABLE);
        assertThat(pv.code()).isEqualTo("987654");
        assertThat(pv.codeExpiresAt()).isEqualTo(expires);
        assertThat(pv.botTaskId()).isNull();
        assertThat(pv.instructions()).isNull();
    }

    @Test
    void buildPendingVerification_rezzable_noActiveCode_returnsNull() {
        Auction a = build(AuctionStatus.VERIFICATION_PENDING);
        when(verificationCodeService.findActiveForParcel(SELLER_ID, AUCTION_ID))
                .thenReturn(Optional.empty());

        assertThat(service.buildPendingVerification(a)).isNull();
    }

    @Test
    void buildPendingVerification_rezzable_nonPendingStatus_returnsNull() {
        Auction a = build(AuctionStatus.ACTIVE);

        assertThat(service.buildPendingVerification(a)).isNull();
        // No code lookup should happen for non-pending auctions
        verify(verificationCodeService, org.mockito.Mockito.never())
                .findActiveForParcel(eq(SELLER_ID), eq(AUCTION_ID));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Auction build(AuctionStatus status) {
        return Auction.builder()
                .id(AUCTION_ID).seller(seller).parcel(parcel).status(status)
                .verificationMethod(VerificationMethod.REZZABLE)
                .startingBid(1000L).durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(status != AuctionStatus.DRAFT)
                .currentBid(0L).bidCount(0)
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .tags(new HashSet<>())
                .createdAt(OffsetDateTime.now(fixed))
                .updatedAt(OffsetDateTime.now(fixed))
                .build();
    }
}
