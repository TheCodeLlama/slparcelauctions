package com.slparcelauctions.backend.sl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.AuctionStatusConstants;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.auction.exception.ParcelAlreadyListedException;
import com.slparcelauctions.backend.auction.monitoring.OwnershipCheckTimestampInitializer;
import com.slparcelauctions.backend.auction.monitoring.config.OwnershipMonitorProperties;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.sl.dto.SlParcelVerifyRequest;
import com.slparcelauctions.backend.sl.exception.InvalidSlHeadersException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.verification.VerificationCode;
import com.slparcelauctions.backend.verification.VerificationCodeRepository;
import com.slparcelauctions.backend.verification.VerificationCodeType;
import com.slparcelauctions.backend.verification.exception.CodeNotFoundException;

/**
 * Unit coverage for {@link SlParcelVerifyService}. Exercises each branch of
 * the LSL-callback happy path and the rejection paths: header validation,
 * code lookup, auction state, parcel/owner mismatch, and parcel lock.
 */
class SlParcelVerifyServiceTest {

    private static final UUID TRUSTED = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String TRUSTED_STR = TRUSTED.toString();
    private static final UUID PARCEL_UUID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SELLER_AVATAR = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_AVATAR = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final Long AUCTION_ID = 77L;
    private static final Long PARCEL_ID = 9L;
    private static final Long SELLER_ID = 42L;
    private static final String CODE = "123456";

    private static final OffsetDateTime NOW = OffsetDateTime.of(
            2026, 4, 16, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final Clock FIXED = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);

    private VerificationCodeRepository codeRepo;
    private AuctionRepository auctionRepo;
    private ParcelRepository parcelRepo;
    private SlHeaderValidator headerValidator;
    private SlParcelVerifyService service;

    private User seller;
    private Parcel parcel;
    private Auction auction;
    private VerificationCode code;

    @BeforeEach
    void setUp() {
        codeRepo = mock(VerificationCodeRepository.class);
        auctionRepo = mock(AuctionRepository.class);
        parcelRepo = mock(ParcelRepository.class);
        headerValidator = new SlHeaderValidator(
                new SlConfigProperties("Production", Set.of(TRUSTED)));
        OwnershipMonitorProperties ownershipProps = new OwnershipMonitorProperties();
        OwnershipCheckTimestampInitializer ownershipInit =
                new OwnershipCheckTimestampInitializer(ownershipProps, FIXED);
        service = new SlParcelVerifyService(
                headerValidator, codeRepo, auctionRepo, parcelRepo, ownershipInit, FIXED);

        seller = User.builder().id(SELLER_ID).email("s@example.com")
                .slAvatarUuid(SELLER_AVATAR).verified(true).build();
        parcel = Parcel.builder().id(PARCEL_ID).slParcelUuid(PARCEL_UUID)
                .ownerUuid(SELLER_AVATAR).ownerType("agent")
                .regionName("Coniston").continentName("Sansara").verified(true).build();
        auction = Auction.builder()
                .title("Test listing")
                .id(AUCTION_ID).seller(seller).parcel(parcel)
                .status(AuctionStatus.VERIFICATION_PENDING)
                .verificationMethod(VerificationMethod.REZZABLE)
                .startingBid(1000L).durationHours(168)
                .snipeProtect(false).listingFeePaid(true)
                .currentBid(0L).bidCount(0)
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .tags(new HashSet<>())
                .build();
        code = VerificationCode.builder()
                .id(7L).userId(SELLER_ID).auctionId(AUCTION_ID)
                .code(CODE).type(VerificationCodeType.PARCEL)
                .expiresAt(NOW.plusMinutes(10)).used(false).build();

        // Default happy-path stubs; individual tests override as needed.
        lenient().when(codeRepo.findByCodeAndTypeAndUsedFalseAndExpiresAtAfter(
                CODE, VerificationCodeType.PARCEL, NOW)).thenReturn(List.of(code));
        lenient().when(auctionRepo.findById(AUCTION_ID)).thenReturn(Optional.of(auction));
        lenient().when(auctionRepo.existsByParcelIdAndStatusInAndIdNot(
                anyLong(), anyCollection(), anyLong())).thenReturn(false);
        lenient().when(auctionRepo.saveAndFlush(any(Auction.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(codeRepo.save(any(VerificationCode.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(parcelRepo.save(any(Parcel.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void happyPath_transitionsAuctionToActive_usesCode_refreshesParcel() {
        service.verify("Production", TRUSTED_STR, body());

        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(auction.getVerificationTier()).isEqualTo(VerificationTier.SCRIPT);
        assertThat(auction.getStartsAt()).isEqualTo(NOW);
        assertThat(auction.getEndsAt()).isEqualTo(NOW.plusHours(168));
        assertThat(auction.getOriginalEndsAt()).isEqualTo(NOW.plusHours(168));
        assertThat(auction.getVerifiedAt()).isEqualTo(NOW);
        assertThat(code.isUsed()).isTrue();
        // Parcel metadata refreshed from the LSL report
        assertThat(parcel.getAreaSqm()).isEqualTo(1024);
        assertThat(parcel.getPositionX()).isEqualTo(128.0);
        assertThat(parcel.getPositionY()).isEqualTo(64.0);
        assertThat(parcel.getPositionZ()).isEqualTo(22.0);
        assertThat(parcel.getLastChecked()).isEqualTo(NOW);
        verify(auctionRepo).saveAndFlush(auction);
    }

    // -------------------------------------------------------------------------
    // Header validation
    // -------------------------------------------------------------------------

    @Test
    void wrongShardHeader_throwsInvalidSlHeaders_doesNotTouchDb() {
        assertThatThrownBy(() -> service.verify("Beta", TRUSTED_STR, body()))
                .isInstanceOf(InvalidSlHeadersException.class);
        verify(codeRepo, never()).findByCodeAndTypeAndUsedFalseAndExpiresAtAfter(any(), any(), any());
        verify(auctionRepo, never()).findById(any());
    }

    @Test
    void untrustedOwnerKey_throwsInvalidSlHeaders() {
        UUID untrusted = UUID.fromString("11111111-1111-1111-1111-111111111111");
        assertThatThrownBy(() -> service.verify("Production", untrusted.toString(), body()))
                .isInstanceOf(InvalidSlHeadersException.class);
        verify(codeRepo, never()).findByCodeAndTypeAndUsedFalseAndExpiresAtAfter(any(), any(), any());
    }

    @Test
    void missingOwnerKey_throwsInvalidSlHeaders() {
        assertThatThrownBy(() -> service.verify("Production", null, body()))
                .isInstanceOf(InvalidSlHeadersException.class);
    }

    // -------------------------------------------------------------------------
    // Code lookup failures
    // -------------------------------------------------------------------------

    @Test
    void codeNotFound_throwsCodeNotFound() {
        when(codeRepo.findByCodeAndTypeAndUsedFalseAndExpiresAtAfter(
                CODE, VerificationCodeType.PARCEL, NOW)).thenReturn(List.of());

        assertThatThrownBy(() -> service.verify("Production", TRUSTED_STR, body()))
                .isInstanceOf(CodeNotFoundException.class);
        verify(auctionRepo, never()).findById(any());
    }

    @Test
    void codeCollision_voidsBothAndRejects() {
        VerificationCode dup = VerificationCode.builder()
                .id(8L).userId(99L).auctionId(AUCTION_ID + 1)
                .code(CODE).type(VerificationCodeType.PARCEL)
                .expiresAt(NOW.plusMinutes(5)).used(false).build();
        when(codeRepo.findByCodeAndTypeAndUsedFalseAndExpiresAtAfter(
                CODE, VerificationCodeType.PARCEL, NOW))
                .thenReturn(List.of(code, dup));

        assertThatThrownBy(() -> service.verify("Production", TRUSTED_STR, body()))
                .isInstanceOf(CodeNotFoundException.class);
        assertThat(code.isUsed()).isTrue();
        assertThat(dup.isUsed()).isTrue();
        verify(codeRepo).saveAll(List.of(code, dup));
    }

    @Test
    void codeHasNoAuctionBinding_throwsIllegalState() {
        VerificationCode orphan = VerificationCode.builder()
                .id(7L).userId(SELLER_ID).auctionId(null)
                .code(CODE).type(VerificationCodeType.PARCEL)
                .expiresAt(NOW.plusMinutes(10)).used(false).build();
        when(codeRepo.findByCodeAndTypeAndUsedFalseAndExpiresAtAfter(
                CODE, VerificationCodeType.PARCEL, NOW)).thenReturn(List.of(orphan));

        assertThatThrownBy(() -> service.verify("Production", TRUSTED_STR, body()))
                .isInstanceOf(IllegalStateException.class);
    }

    // -------------------------------------------------------------------------
    // Auction state failures
    // -------------------------------------------------------------------------

    @Test
    void auctionNotFound_throwsAuctionNotFound() {
        when(auctionRepo.findById(AUCTION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify("Production", TRUSTED_STR, body()))
                .isInstanceOf(AuctionNotFoundException.class);
    }

    @Test
    void auctionNotInVerificationPending_throwsInvalidState() {
        auction.setStatus(AuctionStatus.ACTIVE);

        assertThatThrownBy(() -> service.verify("Production", TRUSTED_STR, body()))
                .isInstanceOf(InvalidAuctionStateException.class);
    }

    @Test
    void auctionMethodFlippedAwayFromRezzable_voidsCodeAndThrowsInvalidState() {
        // Seller toggled to UUID_ENTRY after the code was issued. The stale
        // PARCEL code must be burned so a later LSL callback cannot re-fire it.
        auction.setVerificationMethod(VerificationMethod.UUID_ENTRY);

        assertThatThrownBy(() -> service.verify("Production", TRUSTED_STR, body()))
                .isInstanceOf(InvalidAuctionStateException.class);
        assertThat(code.isUsed()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Ownership + parcel mismatch
    // -------------------------------------------------------------------------

    @Test
    void parcelUuidMismatch_throwsIllegalArgument() {
        UUID wrongParcel = UUID.fromString("99999999-9999-9999-9999-999999999999");
        SlParcelVerifyRequest b = new SlParcelVerifyRequest(
                CODE, wrongParcel, SELLER_AVATAR,
                "Parcel", 1024, "desc", 234, 128.0, 64.0, 22.0);

        assertThatThrownBy(() -> service.verify("Production", TRUSTED_STR, b))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Parcel UUID mismatch");
        // Code not consumed when the ownership claim is invalid
        assertThat(code.isUsed()).isFalse();
    }

    @Test
    void ownerUuidMismatch_throwsIllegalArgument() {
        SlParcelVerifyRequest b = new SlParcelVerifyRequest(
                CODE, PARCEL_UUID, OTHER_AVATAR,
                "Parcel", 1024, "desc", 234, 128.0, 64.0, 22.0);

        assertThatThrownBy(() -> service.verify("Production", TRUSTED_STR, b))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Owner UUID mismatch");
        assertThat(code.isUsed()).isFalse();
    }

    @Test
    void sellerHasNoLinkedAvatar_throwsIllegalArgument() {
        seller.setSlAvatarUuid(null);

        assertThatThrownBy(() -> service.verify("Production", TRUSTED_STR, body()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Owner UUID mismatch");
    }

    // -------------------------------------------------------------------------
    // Parcel lock
    // -------------------------------------------------------------------------

    @Test
    void parcelLockedByAnotherActiveAuction_throwsParcelAlreadyListed() {
        when(auctionRepo.existsByParcelIdAndStatusInAndIdNot(
                PARCEL_ID, AuctionStatusConstants.LOCKING_STATUSES, AUCTION_ID))
                .thenReturn(true);
        Auction blocker = Auction.builder().title("Test listing").id(55L).build();
        when(auctionRepo.findFirstByParcelIdAndStatusIn(
                PARCEL_ID, AuctionStatusConstants.LOCKING_STATUSES))
                .thenReturn(Optional.of(blocker));

        assertThatThrownBy(() -> service.verify("Production", TRUSTED_STR, body()))
                .isInstanceOfSatisfying(ParcelAlreadyListedException.class, ex -> {
                    assertThat(ex.getParcelId()).isEqualTo(PARCEL_ID);
                    assertThat(ex.getBlockingAuctionId()).isEqualTo(55L);
                });
        // Code not consumed on lock failure
        assertThat(code.isUsed()).isFalse();
    }

    @Test
    void saveAndFlush_dataIntegrityViolation_throwsParcelAlreadyListedSentinel() {
        when(auctionRepo.saveAndFlush(any(Auction.class)))
                .thenThrow(new DataIntegrityViolationException("uq_auctions_parcel_locked_status"));

        assertThatThrownBy(() -> service.verify("Production", TRUSTED_STR, body()))
                .isInstanceOfSatisfying(ParcelAlreadyListedException.class, ex -> {
                    assertThat(ex.getParcelId()).isEqualTo(PARCEL_ID);
                    assertThat(ex.getBlockingAuctionId()).isEqualTo(-1L);
                });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SlParcelVerifyRequest body() {
        return new SlParcelVerifyRequest(
                CODE, PARCEL_UUID, SELLER_AVATAR,
                "Test Parcel", 1024, "desc", 234,
                128.0, 64.0, 22.0);
    }
}
