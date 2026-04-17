package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
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
import org.springframework.dao.DataIntegrityViolationException;

import com.slparcelauctions.backend.auction.exception.GroupLandRequiresSaleToBotException;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.auction.exception.ParcelAlreadyListedException;
import com.slparcelauctions.backend.bot.BotTaskRepository;
import com.slparcelauctions.backend.bot.BotTaskService;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.exception.ExternalApiTimeoutException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.verification.VerificationCodeService;

import reactor.core.publisher.Mono;

/**
 * Unit coverage for {@link AuctionVerificationService} Method A (UUID_ENTRY).
 * Real integrations (DB, Postgres partial unique index) are covered by
 * {@link ParcelLockingRaceIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class AuctionVerificationServiceMethodATest {

    private static final Long SELLER_ID = 42L;
    private static final Long AUCTION_ID = 1L;
    private static final Long PARCEL_ID = 100L;
    private static final UUID PARCEL_UUID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SELLER_AVATAR = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_AVATAR = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

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
        lenient().when(auctionRepo.saveAndFlush(any(Auction.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(auctionRepo.existsByParcelIdAndStatusInAndIdNot(
                        anyLong(), anyCollection(), anyLong()))
                .thenReturn(false);
    }

    // -------------------------------------------------------------------------
    // Happy paths
    // -------------------------------------------------------------------------

    @Test
    void verify_fromDraftPaid_withMatchingOwnership_transitionsToActive() {
        Auction a = build(AuctionStatus.DRAFT_PAID);
        when(auctionService.loadForSeller(AUCTION_ID, SELLER_ID)).thenReturn(a);
        when(worldApi.fetchParcel(PARCEL_UUID))
                .thenReturn(Mono.just(meta(SELLER_AVATAR, "agent")));

        Auction out = service.triggerVerification(AUCTION_ID, VerificationMethod.UUID_ENTRY, SELLER_ID);

        assertThat(out.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(out.getVerificationTier()).isEqualTo(VerificationTier.SCRIPT);
        assertThat(out.getStartsAt()).isEqualTo(OffsetDateTime.now(fixed));
        assertThat(out.getEndsAt()).isEqualTo(OffsetDateTime.now(fixed).plusHours(168));
        assertThat(out.getOriginalEndsAt()).isEqualTo(out.getEndsAt());
        assertThat(out.getVerifiedAt()).isEqualTo(OffsetDateTime.now(fixed));
        assertThat(out.getVerificationNotes()).isNull();
        // Parcel ownership fields refreshed from World API
        assertThat(out.getParcel().getOwnerUuid()).isEqualTo(SELLER_AVATAR);
        assertThat(out.getParcel().getOwnerType()).isEqualTo("agent");
        assertThat(out.getParcel().getLastChecked()).isEqualTo(OffsetDateTime.now(fixed));
    }

    @Test
    void verify_fromVerificationFailed_retry_withMatchingOwnership_transitionsToActive() {
        Auction a = build(AuctionStatus.VERIFICATION_FAILED);
        a.setVerificationNotes("stale failure note");
        when(auctionService.loadForSeller(AUCTION_ID, SELLER_ID)).thenReturn(a);
        when(worldApi.fetchParcel(PARCEL_UUID))
                .thenReturn(Mono.just(meta(SELLER_AVATAR, "agent")));

        Auction out = service.triggerVerification(AUCTION_ID, VerificationMethod.UUID_ENTRY, SELLER_ID);

        assertThat(out.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        // Prior failure note cleared on retry
        assertThat(out.getVerificationNotes()).isNull();
    }

    // -------------------------------------------------------------------------
    // Disallowed source statuses
    // -------------------------------------------------------------------------

    @Test
    void verify_fromDraft_unpaid_throwsInvalidState() {
        Auction a = build(AuctionStatus.DRAFT);
        when(auctionService.loadForSeller(AUCTION_ID, SELLER_ID)).thenReturn(a);

        assertThatThrownBy(() -> service.triggerVerification(AUCTION_ID, VerificationMethod.UUID_ENTRY, SELLER_ID))
                .isInstanceOf(InvalidAuctionStateException.class);
    }

    @Test
    void verify_fromActive_throwsInvalidState() {
        Auction a = build(AuctionStatus.ACTIVE);
        when(auctionService.loadForSeller(AUCTION_ID, SELLER_ID)).thenReturn(a);

        assertThatThrownBy(() -> service.triggerVerification(AUCTION_ID, VerificationMethod.UUID_ENTRY, SELLER_ID))
                .isInstanceOf(InvalidAuctionStateException.class);
    }

    // -------------------------------------------------------------------------
    // Method A rejection paths
    // -------------------------------------------------------------------------

    @Test
    void verify_withGroupOwnerType_transitionsToVerificationFailed() {
        Auction a = build(AuctionStatus.DRAFT_PAID);
        when(auctionService.loadForSeller(AUCTION_ID, SELLER_ID)).thenReturn(a);
        when(worldApi.fetchParcel(PARCEL_UUID))
                .thenReturn(Mono.just(meta(SELLER_AVATAR, "group")));

        Auction out = service.triggerVerification(AUCTION_ID, VerificationMethod.UUID_ENTRY, SELLER_ID);

        assertThat(out.getStatus()).isEqualTo(AuctionStatus.VERIFICATION_FAILED);
        assertThat(out.getVerificationNotes()).contains("rejects group-owned");
    }

    @Test
    void verify_withOwnerUuidMismatch_transitionsToVerificationFailed_withRetryFriendlyNotes() {
        // Sub-spec 2 §7.3: synchronous Method A failures land in
        // VERIFICATION_FAILED with human-readable verificationNotes explaining
        // what went wrong and that the seller can retry. No ListingFeeRefund
        // is created — AuctionVerificationService doesn't even hold a reference
        // to ListingFeeRefundRepository, so refund creation is structurally
        // impossible from this path (refunds only happen via explicit cancel).
        Auction a = build(AuctionStatus.DRAFT_PAID);
        when(auctionService.loadForSeller(AUCTION_ID, SELLER_ID)).thenReturn(a);
        when(worldApi.fetchParcel(PARCEL_UUID))
                .thenReturn(Mono.just(meta(OTHER_AVATAR, "agent")));

        Auction out = service.triggerVerification(AUCTION_ID, VerificationMethod.UUID_ENTRY, SELLER_ID);

        assertThat(out.getStatus()).isEqualTo(AuctionStatus.VERIFICATION_FAILED);
        assertThat(out.getVerificationNotes())
                .contains("Ownership check failed")
                .contains("owner UUID doesn't match");
    }

    @Test
    void verify_worldApiTimeoutException_transitionsToVerificationFailed() {
        Auction a = build(AuctionStatus.DRAFT_PAID);
        when(auctionService.loadForSeller(AUCTION_ID, SELLER_ID)).thenReturn(a);
        when(worldApi.fetchParcel(PARCEL_UUID))
                .thenReturn(Mono.error(new ExternalApiTimeoutException("World", "upstream 503")));

        Auction out = service.triggerVerification(AUCTION_ID, VerificationMethod.UUID_ENTRY, SELLER_ID);

        assertThat(out.getStatus()).isEqualTo(AuctionStatus.VERIFICATION_FAILED);
        assertThat(out.getVerificationNotes()).contains("World API lookup failed");
    }

    // -------------------------------------------------------------------------
    // Sub-spec 2 §7.2 — method is chosen on the verify trigger, not at create
    // -------------------------------------------------------------------------

    @Test
    void triggerVerification_acceptsMethodFromRequest_andPersistsIt() {
        // Simulate a DRAFT_PAID auction created under sub-spec 2 where
        // verificationMethod is null until the seller picks one at activate
        // time. Dispatch must persist the chosen method on the entity.
        Auction a = build(AuctionStatus.DRAFT_PAID);
        a.setVerificationMethod(null);
        when(auctionService.loadForSeller(AUCTION_ID, SELLER_ID)).thenReturn(a);
        when(worldApi.fetchParcel(PARCEL_UUID))
                .thenReturn(Mono.just(meta(SELLER_AVATAR, "agent")));

        Auction out = service.triggerVerification(
                AUCTION_ID, VerificationMethod.UUID_ENTRY, SELLER_ID);

        assertThat(out.getVerificationMethod()).isEqualTo(VerificationMethod.UUID_ENTRY);
    }

    @Test
    void triggerVerification_groupOwned_nonSaleToBotMethod_throws422() {
        // Parcel owned by a group — UUID_ENTRY cannot resolve to a single
        // seller avatar, so the service rejects the request with 422.
        Auction a = build(AuctionStatus.DRAFT_PAID);
        a.getParcel().setOwnerType("group");
        when(auctionService.loadForSeller(AUCTION_ID, SELLER_ID)).thenReturn(a);

        assertThatThrownBy(() -> service.triggerVerification(
                AUCTION_ID, VerificationMethod.UUID_ENTRY, SELLER_ID))
                .isInstanceOf(GroupLandRequiresSaleToBotException.class);
    }

    @Test
    void triggerVerification_groupOwned_rezzableMethod_throws422() {
        // REZZABLE also fails for group land — the in-world object sees the
        // group key, not the seller. Only SALE_TO_BOT can transfer group land.
        Auction a = build(AuctionStatus.DRAFT_PAID);
        a.getParcel().setOwnerType("group");
        when(auctionService.loadForSeller(AUCTION_ID, SELLER_ID)).thenReturn(a);

        assertThatThrownBy(() -> service.triggerVerification(
                AUCTION_ID, VerificationMethod.REZZABLE, SELLER_ID))
                .isInstanceOf(GroupLandRequiresSaleToBotException.class);
    }

    @Test
    void triggerVerification_groupOwned_saleToBot_succeedsAndStaysPending() {
        // Group-owned parcel + SALE_TO_BOT — the only permitted combination.
        // Leaves the auction in VERIFICATION_PENDING (Method C is async; bot
        // worker callback drives the ACTIVE transition).
        Auction a = build(AuctionStatus.DRAFT_PAID);
        a.getParcel().setOwnerType("group");
        when(auctionService.loadForSeller(AUCTION_ID, SELLER_ID)).thenReturn(a);

        Auction out = service.triggerVerification(
                AUCTION_ID, VerificationMethod.SALE_TO_BOT, SELLER_ID);

        assertThat(out.getStatus()).isEqualTo(AuctionStatus.VERIFICATION_PENDING);
        assertThat(out.getVerificationMethod()).isEqualTo(VerificationMethod.SALE_TO_BOT);
    }

    // -------------------------------------------------------------------------
    // Parcel-lock enforcement
    // -------------------------------------------------------------------------

    @Test
    void verify_parcelLockedByAnotherActiveAuction_throwsParcelAlreadyListed() {
        Auction a = build(AuctionStatus.DRAFT_PAID);
        when(auctionService.loadForSeller(AUCTION_ID, SELLER_ID)).thenReturn(a);
        when(worldApi.fetchParcel(PARCEL_UUID))
                .thenReturn(Mono.just(meta(SELLER_AVATAR, "agent")));
        when(auctionRepo.existsByParcelIdAndStatusInAndIdNot(
                PARCEL_ID, AuctionStatusConstants.LOCKING_STATUSES, AUCTION_ID))
                .thenReturn(true);
        Auction blocker = build(AuctionStatus.ACTIVE);
        blocker.setId(77L);
        when(auctionRepo.findFirstByParcelIdAndStatusIn(
                PARCEL_ID, AuctionStatusConstants.LOCKING_STATUSES))
                .thenReturn(Optional.of(blocker));

        assertThatThrownBy(() -> service.triggerVerification(AUCTION_ID, VerificationMethod.UUID_ENTRY, SELLER_ID))
                .isInstanceOfSatisfying(ParcelAlreadyListedException.class, ex -> {
                    assertThat(ex.getParcelId()).isEqualTo(PARCEL_ID);
                    assertThat(ex.getBlockingAuctionId()).isEqualTo(77L);
                });
    }

    @Test
    void verify_dataIntegrityViolationOnSave_throwsParcelAlreadyListedWithSentinel() {
        Auction a = build(AuctionStatus.DRAFT_PAID);
        when(auctionService.loadForSeller(AUCTION_ID, SELLER_ID)).thenReturn(a);
        when(worldApi.fetchParcel(PARCEL_UUID))
                .thenReturn(Mono.just(meta(SELLER_AVATAR, "agent")));
        // Pre-check passes (exists=false) but the final saveAndFlush trips the unique index.
        when(auctionRepo.saveAndFlush(any(Auction.class)))
                .thenThrow(new DataIntegrityViolationException("uq_auctions_parcel_locked_status"));

        assertThatThrownBy(() -> service.triggerVerification(AUCTION_ID, VerificationMethod.UUID_ENTRY, SELLER_ID))
                .isInstanceOfSatisfying(ParcelAlreadyListedException.class, ex -> {
                    assertThat(ex.getParcelId()).isEqualTo(PARCEL_ID);
                    assertThat(ex.getBlockingAuctionId()).isEqualTo(-1L);
                });
    }

    // -------------------------------------------------------------------------
    // buildPendingVerification
    // -------------------------------------------------------------------------

    @Test
    void buildPendingVerification_methodA_alwaysReturnsNull() {
        Auction a = build(AuctionStatus.VERIFICATION_PENDING);
        assertThat(service.buildPendingVerification(a)).isNull();
    }

    @Test
    void buildPendingVerification_activeAuction_returnsNull() {
        Auction a = build(AuctionStatus.ACTIVE);
        assertThat(service.buildPendingVerification(a)).isNull();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Auction build(AuctionStatus status) {
        return Auction.builder()
                .id(AUCTION_ID).seller(seller).parcel(parcel).status(status)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
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

    private ParcelMetadata meta(UUID owner, String ownerType) {
        return new ParcelMetadata(
                PARCEL_UUID, owner, ownerType,
                "Test Parcel", "Coniston",
                1024, "desc", "http://example.com/snap.jpg", "MATURE",
                128.0, 64.0, 22.0);
    }
}
