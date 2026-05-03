package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.slparcelauctions.backend.admin.ban.BanCheckService;
import com.slparcelauctions.backend.auction.exception.SellerSuspendedException;
import com.slparcelauctions.backend.auction.exception.SuspensionReason;

import com.slparcelauctions.backend.auction.dto.AuctionCreateRequest;
import com.slparcelauctions.backend.auction.dto.AuctionUpdateRequest;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.parceltag.ParcelTag;
import com.slparcelauctions.backend.parceltag.ParcelTagRepository;
import com.slparcelauctions.backend.parcel.ParcelLookupService;
import com.slparcelauctions.backend.parcel.ParcelLookupService.ParcelLookupResult;
import com.slparcelauctions.backend.parcel.dto.ParcelResponse;
import com.slparcelauctions.backend.region.Region;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.testsupport.TestRegions;

@ExtendWith(MockitoExtension.class)
class AuctionServiceTest {

    @Mock AuctionRepository auctionRepo;
    @Mock ParcelLookupService parcelLookupService;
    @Mock ParcelSnapshotPhotoService parcelSnapshotPhotoService;
    @Mock UserRepository userRepo;
    @Mock ParcelTagRepository tagRepo;
    @Mock BanCheckService banCheckService;
    @Spy Clock clock = Clock.fixed(Instant.parse("2026-04-24T10:00:00Z"), ZoneOffset.UTC);

    @InjectMocks AuctionService service;

    private static final UUID PARCEL_UUID = UUID.randomUUID();
    private User seller;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "defaultCommissionRate", new BigDecimal("0.05"));
        seller = User.builder().id(42L).email("s@example.com").verified(true).build();
        lenient().when(userRepo.findById(42L)).thenReturn(Optional.of(seller));
        Region region = TestRegions.mainland();
        ParcelResponse response = new ParcelResponse(
                PARCEL_UUID, UUID.randomUUID(), "agent", null, "Test Parcel",
                null, "TestRegion", "GENERAL", 1014.0, 1014.0,
                128.0, 64.0, 22.0, 1024, null, null, null, true, null, null);
        lenient().when(parcelLookupService.lookup(PARCEL_UUID))
                .thenReturn(new ParcelLookupResult(response, region));
        lenient().when(auctionRepo.save(any(Auction.class))).thenAnswer(inv -> {
            Auction a = inv.getArgument(0);
            if (a.getId() == null) a.setId(1L);
            return a;
        });
        // refreshFor is void — Mockito auto-stubs void methods on mocks; no explicit stub needed.
    }

    // -------------------------------------------------------------------------
    // create(): happy path
    // -------------------------------------------------------------------------

    @Test
    void create_validRequest_savesInDraft() {
        AuctionCreateRequest req = new AuctionCreateRequest(
                PARCEL_UUID, "Test listing", 1000L, null, null,
                168, false, null, "Nice parcel", Set.of());

        Auction a = service.create(42L, req, null);

        assertThat(a.getStatus()).isEqualTo(AuctionStatus.DRAFT);
        assertThat(a.getSeller().getId()).isEqualTo(42L);
        assertThat(a.getSlParcelUuid()).isEqualTo(PARCEL_UUID);
        assertThat(a.getListingFeePaid()).isFalse();
        assertThat(a.getCommissionRate()).isEqualByComparingTo(new BigDecimal("0.05"));
    }

    // -------------------------------------------------------------------------
    // create(): title field (Epic 07 sub-spec 1)
    // -------------------------------------------------------------------------

    @Test
    void create_persistsTitle() {
        AuctionCreateRequest req = minimalCreateRequest("Premium Waterfront — Must Sell!");

        Auction created = service.create(42L, req, null);

        assertThat(created.getTitle()).isEqualTo("Premium Waterfront — Must Sell!");
    }

    @Test
    void create_blankTitle_throwsValidation() {
        // Service-side guard mirrors the @NotBlank on AuctionCreateRequest so
        // callers that bypass MockMvc validation still hit a hard failure.
        AuctionCreateRequest req = minimalCreateRequest("   ");

        assertThatThrownBy(() -> service.create(42L, req, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_nullTitle_throwsValidation() {
        // The @NotBlank on AuctionCreateRequest is enforced at the controller
        // boundary; this asserts the service-direct guard rejects null too so
        // the invariant holds for every entry point.
        AuctionCreateRequest req = minimalCreateRequest(null);

        assertThatThrownBy(() -> service.create(42L, req, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_titleTooLong_throwsValidation() {
        // Mirrors the @Size(max = 120) on AuctionCreateRequest.title — direct
        // service callers must not be able to write past the column length.
        String over120 = "x".repeat(121);
        AuctionCreateRequest req = minimalCreateRequest(over120);

        assertThatThrownBy(() -> service.create(42L, req, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 120 characters");
    }

    @Test
    void update_titleTooLong_throwsValidation() {
        // Symmetric with create's length guard so partial updates can't write
        // past the column length even when the controller-side @Size is bypassed.
        Auction existing = buildAuction(AuctionStatus.DRAFT);
        when(auctionRepo.findByIdAndSellerId(1L, 42L)).thenReturn(Optional.of(existing));

        String over120 = "x".repeat(121);
        AuctionUpdateRequest req = new AuctionUpdateRequest(
                null, over120, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.update(1L, 42L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 120 characters");
    }

    @Test
    void update_blankTitle_throwsValidation() {
        // Per the partial-update contract, null = "don't touch" but an explicit
        // blank must still be rejected so the @NotBlank invariant on create
        // isn't laundered through the update path.
        Auction existing = buildAuction(AuctionStatus.DRAFT);
        when(auctionRepo.findByIdAndSellerId(1L, 42L)).thenReturn(Optional.of(existing));

        AuctionUpdateRequest req = new AuctionUpdateRequest(
                null, "   ", null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.update(1L, 42L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title must not be blank");
    }

    @Test
    void update_nullTitle_leavesExistingUnchanged() {
        Auction existing = buildAuction(AuctionStatus.DRAFT);
        existing.setTitle("Original");
        when(auctionRepo.findByIdAndSellerId(1L, 42L)).thenReturn(Optional.of(existing));

        AuctionUpdateRequest req = new AuctionUpdateRequest(
                null, null, 2000L, null, null, null, null, null, null, null);
        Auction updated = service.update(1L, 42L, req);

        assertThat(updated.getTitle()).isEqualTo("Original");
    }

    @Test
    void update_validTitle_overwrites() {
        Auction existing = buildAuction(AuctionStatus.DRAFT);
        existing.setTitle("Original");
        when(auctionRepo.findByIdAndSellerId(1L, 42L)).thenReturn(Optional.of(existing));

        AuctionUpdateRequest req = new AuctionUpdateRequest(
                null, "Renamed listing", null, null, null, null, null, null, null, null);
        Auction updated = service.update(1L, 42L, req);

        assertThat(updated.getTitle()).isEqualTo("Renamed listing");
    }

    // -------------------------------------------------------------------------
    // create(): suspension gate — Epic 08 sub-spec 2 §7.7
    //
    // Order is most-restrictive-first: ban → timed → penalty. The first
    // matching condition surfaces as the SellerSuspendedException's reason
    // and rides the 403 ProblemDetail's "code" property.
    // -------------------------------------------------------------------------

    @Test
    void create_throwsPenaltyOwed_whenPenaltyBalancePositive() {
        seller.setPenaltyBalanceOwed(500L);
        AuctionCreateRequest req = minimalCreateRequest("Test listing");

        SellerSuspendedException ex = org.junit.jupiter.api.Assertions.assertThrows(
                SellerSuspendedException.class,
                () -> service.create(42L, req, null));

        assertThat(ex.getReason()).isEqualTo(SuspensionReason.PENALTY_OWED);
    }

    @Test
    void create_throwsTimedSuspension_whenSuspensionUntilFuture() {
        seller.setListingSuspensionUntil(OffsetDateTime.now(clock).plusDays(5));
        AuctionCreateRequest req = minimalCreateRequest("Test listing");

        SellerSuspendedException ex = org.junit.jupiter.api.Assertions.assertThrows(
                SellerSuspendedException.class,
                () -> service.create(42L, req, null));

        assertThat(ex.getReason()).isEqualTo(SuspensionReason.TIMED_SUSPENSION);
    }

    @Test
    void create_succeeds_whenSuspensionUntilInPast() {
        // Boundary: an expired suspension does not gate the create.
        seller.setListingSuspensionUntil(OffsetDateTime.now(clock).minusSeconds(1));
        AuctionCreateRequest req = minimalCreateRequest("Test listing");

        Auction created = service.create(42L, req, null);

        assertThat(created.getStatus()).isEqualTo(AuctionStatus.DRAFT);
    }

    @Test
    void create_throwsPermanentBan_whenBannedFromListingTrue() {
        seller.setBannedFromListing(true);
        AuctionCreateRequest req = minimalCreateRequest("Test listing");

        SellerSuspendedException ex = org.junit.jupiter.api.Assertions.assertThrows(
                SellerSuspendedException.class,
                () -> service.create(42L, req, null));

        assertThat(ex.getReason()).isEqualTo(SuspensionReason.PERMANENT_BAN);
    }

    @Test
    void create_throwsBan_evenWhenAlsoSuspendedAndOwesPenalty() {
        // Order: ban → timed → penalty. The most-restrictive match wins.
        seller.setBannedFromListing(true);
        seller.setListingSuspensionUntil(OffsetDateTime.now(clock).plusDays(5));
        seller.setPenaltyBalanceOwed(500L);
        AuctionCreateRequest req = minimalCreateRequest("Test listing");

        SellerSuspendedException ex = org.junit.jupiter.api.Assertions.assertThrows(
                SellerSuspendedException.class,
                () -> service.create(42L, req, null));

        assertThat(ex.getReason()).isEqualTo(SuspensionReason.PERMANENT_BAN);
    }

    @Test
    void create_throwsTimed_whenAlsoOwesPenalty() {
        // Timed beats penalty when both are set (and ban isn't).
        seller.setListingSuspensionUntil(OffsetDateTime.now(clock).plusDays(5));
        seller.setPenaltyBalanceOwed(500L);
        AuctionCreateRequest req = minimalCreateRequest("Test listing");

        SellerSuspendedException ex = org.junit.jupiter.api.Assertions.assertThrows(
                SellerSuspendedException.class,
                () -> service.create(42L, req, null));

        assertThat(ex.getReason()).isEqualTo(SuspensionReason.TIMED_SUSPENSION);
    }

    private AuctionCreateRequest minimalCreateRequest(String title) {
        return new AuctionCreateRequest(
                PARCEL_UUID, title, 1000L, null, null,
                168, false, null, null, Set.of());
    }

    @Test
    void create_persistsAuctionWithNullVerificationMethod() {
        // Sub-spec 2 §7.1 — verificationMethod is no longer chosen at create time.
        // It is set on the verify trigger instead; a freshly-created DRAFT auction
        // must have a null verificationMethod.
        AuctionCreateRequest req = new AuctionCreateRequest(
                PARCEL_UUID, "Test listing", 500L, null, null,
                72, true, 10, "Test", Set.of());

        Auction created = service.create(42L, req, null);

        assertThat(created.getStatus()).isEqualTo(AuctionStatus.DRAFT);
        assertThat(created.getVerificationMethod()).isNull();
    }

    @Test
    void create_withSnipeProtect_setsSnipeWindow() {
        AuctionCreateRequest req = new AuctionCreateRequest(
                PARCEL_UUID, "Test listing", 1000L, null, null,
                168, true, 10, null, Set.of());

        Auction a = service.create(42L, req, null);

        assertThat(a.getSnipeProtect()).isTrue();
        assertThat(a.getSnipeWindowMin()).isEqualTo(10);
    }

    // -------------------------------------------------------------------------
    // create(): pricing validation
    // -------------------------------------------------------------------------

    @Test
    void create_reservePriceLessThanStarting_throws() {
        AuctionCreateRequest req = new AuctionCreateRequest(
                PARCEL_UUID, "Test listing", 1000L, 500L, null,
                168, false, null, null, Set.of());

        assertThatThrownBy(() -> service.create(42L, req, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reservePrice must be >= startingBid");
    }

    @Test
    void create_buyNowLessThanReserve_throws() {
        AuctionCreateRequest req = new AuctionCreateRequest(
                PARCEL_UUID, "Test listing", 1000L, 5000L, 4000L,
                168, false, null, null, Set.of());

        assertThatThrownBy(() -> service.create(42L, req, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("buyNowPrice must be >= max");
    }

    @Test
    void create_buyNowLessThanStartingWithNoReserve_throws() {
        AuctionCreateRequest req = new AuctionCreateRequest(
                PARCEL_UUID, "Test listing", 1000L, null, 500L,
                168, false, null, null, Set.of());

        assertThatThrownBy(() -> service.create(42L, req, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("buyNowPrice must be >= max");
    }

    // -------------------------------------------------------------------------
    // create(): duration validation
    // -------------------------------------------------------------------------

    @Test
    void create_durationNotInAllowedSet_throws() {
        AuctionCreateRequest req = new AuctionCreateRequest(
                PARCEL_UUID, "Test listing", 1000L, null, null,
                100, false, null, null, Set.of());

        assertThatThrownBy(() -> service.create(42L, req, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durationHours");
    }

    // -------------------------------------------------------------------------
    // create(): snipe validation
    // -------------------------------------------------------------------------

    @Test
    void create_snipeProtectTrueWithNullWindow_throws() {
        AuctionCreateRequest req = new AuctionCreateRequest(
                PARCEL_UUID, "Test listing", 1000L, null, null,
                168, true, null, null, Set.of());

        assertThatThrownBy(() -> service.create(42L, req, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("snipeWindowMin");
    }

    @Test
    void create_snipeProtectFalseWithWindow_throws() {
        AuctionCreateRequest req = new AuctionCreateRequest(
                PARCEL_UUID, "Test listing", 1000L, null, null,
                168, false, 10, null, Set.of());

        assertThatThrownBy(() -> service.create(42L, req, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("snipeWindowMin must be null");
    }

    @Test
    void create_snipeWindowNotInAllowedSet_throws() {
        AuctionCreateRequest req = new AuctionCreateRequest(
                PARCEL_UUID, "Test listing", 1000L, null, null,
                168, true, 7, null, Set.of());

        assertThatThrownBy(() -> service.create(42L, req, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("snipeWindowMin");
    }

    // -------------------------------------------------------------------------
    // create(): tag resolution
    // -------------------------------------------------------------------------

    @Test
    void create_unknownTagCode_throws() {
        Set<String> codes = Set.of("waterfront", "unknown_tag");
        when(tagRepo.findByCodeIn(codes)).thenReturn(List.of(
                ParcelTag.builder().id(1L).code("waterfront").label("Waterfront")
                        .category("feature").active(true).sortOrder(1).build()));

        AuctionCreateRequest req = new AuctionCreateRequest(
                PARCEL_UUID, "Test listing", 1000L, null, null,
                168, false, null, null, codes);

        assertThatThrownBy(() -> service.create(42L, req, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown parcel tag codes")
                .hasMessageContaining("unknown_tag");
    }

    @Test
    void create_parcelNotFound_throws() {
        UUID unknownUuid = UUID.randomUUID();
        when(parcelLookupService.lookup(unknownUuid))
                .thenThrow(new IllegalArgumentException("Parcel not found: " + unknownUuid));
        AuctionCreateRequest req = new AuctionCreateRequest(
                unknownUuid, "Test listing", 1000L, null, null,
                168, false, null, null, Set.of());

        assertThatThrownBy(() -> service.create(42L, req, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Parcel not found");
    }

    // -------------------------------------------------------------------------
    // update(): state matrix
    // -------------------------------------------------------------------------

    @Test
    void update_draftStatus_succeeds() {
        Auction existing = buildAuction(AuctionStatus.DRAFT);
        when(auctionRepo.findByIdAndSellerId(1L, 42L)).thenReturn(Optional.of(existing));

        AuctionUpdateRequest req = new AuctionUpdateRequest(
                null, null, 2000L, null, null, null, null, null, "updated desc", null);
        Auction updated = service.update(1L, 42L, req);

        assertThat(updated.getStartingBid()).isEqualTo(2000L);
        assertThat(updated.getSellerDesc()).isEqualTo("updated desc");
    }

    @Test
    void update_draftPaidStatus_succeeds() {
        Auction existing = buildAuction(AuctionStatus.DRAFT_PAID);
        when(auctionRepo.findByIdAndSellerId(1L, 42L)).thenReturn(Optional.of(existing));

        AuctionUpdateRequest req = new AuctionUpdateRequest(
                null, null, 2000L, null, null, null, null, null, null, null);
        Auction updated = service.update(1L, 42L, req);

        assertThat(updated.getStartingBid()).isEqualTo(2000L);
    }

    @Test
    void update_verificationPendingStatus_throwsInvalidState() {
        Auction existing = buildAuction(AuctionStatus.VERIFICATION_PENDING);
        when(auctionRepo.findByIdAndSellerId(1L, 42L)).thenReturn(Optional.of(existing));

        AuctionUpdateRequest req = new AuctionUpdateRequest(
                null, null, 2000L, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.update(1L, 42L, req))
                .isInstanceOf(InvalidAuctionStateException.class);
    }

    @Test
    void update_activeStatus_throwsInvalidState() {
        Auction existing = buildAuction(AuctionStatus.ACTIVE);
        when(auctionRepo.findByIdAndSellerId(1L, 42L)).thenReturn(Optional.of(existing));

        AuctionUpdateRequest req = new AuctionUpdateRequest(
                null, null, 2000L, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.update(1L, 42L, req))
                .isInstanceOf(InvalidAuctionStateException.class);
    }

    @Test
    void update_togglesSnipeProtectOff_clearsWindow() {
        Auction existing = buildAuction(AuctionStatus.DRAFT);
        existing.setSnipeProtect(true);
        existing.setSnipeWindowMin(10);
        when(auctionRepo.findByIdAndSellerId(1L, 42L)).thenReturn(Optional.of(existing));

        AuctionUpdateRequest req = new AuctionUpdateRequest(
                null, null, null, null, null, null, false, null, null, null);
        Auction updated = service.update(1L, 42L, req);

        assertThat(updated.getSnipeProtect()).isFalse();
        assertThat(updated.getSnipeWindowMin()).isNull();
    }

    // -------------------------------------------------------------------------
    // load / loadForSeller
    // -------------------------------------------------------------------------

    @Test
    void loadForSeller_wrongSeller_throwsNotFound() {
        when(auctionRepo.findByIdAndSellerId(1L, 99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadForSeller(1L, 99L))
                .isInstanceOf(AuctionNotFoundException.class);
    }

    @Test
    void load_missingAuction_throwsNotFound() {
        when(auctionRepo.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.load(1L))
                .isInstanceOf(AuctionNotFoundException.class);
    }

    @Test
    void loadOwnedBy_returnsRepoList() {
        Auction a = buildAuction(AuctionStatus.DRAFT);
        when(auctionRepo.findBySellerIdOrderByCreatedAtDesc(42L)).thenReturn(List.of(a));

        List<Auction> out = service.loadOwnedBy(42L);

        assertThat(out).containsExactly(a);
    }

    private Auction buildAuction(AuctionStatus status) {
        Auction a = Auction.builder()
                .title("Test listing")
                .id(1L).seller(seller).slParcelUuid(PARCEL_UUID).status(status)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .startingBid(1000L).durationHours(168)
                .snipeProtect(false).snipeWindowMin(null)
                .currentBid(0L).bidCount(0)
                .listingFeePaid(false)
                .commissionRate(new BigDecimal("0.05"))
                .agentFeeRate(BigDecimal.ZERO)
                .tags(new HashSet<>())
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();
        AuctionParcelSnapshot snap = AuctionParcelSnapshot.builder()
                .slParcelUuid(PARCEL_UUID)
                .ownerUuid(UUID.randomUUID())
                .ownerType("agent")
                .parcelName("Test Parcel")
                .region(TestRegions.mainland())
                .regionName("TestRegion")
                .regionMaturityRating("GENERAL")
                .areaSqm(1024)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build();
        a.setParcelSnapshot(snap);
        return a;
    }
}
