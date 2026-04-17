package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.slparcelauctions.backend.auction.dto.AuctionCreateRequest;
import com.slparcelauctions.backend.auction.dto.AuctionUpdateRequest;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.parceltag.ParcelTag;
import com.slparcelauctions.backend.parceltag.ParcelTagRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class AuctionServiceTest {

    @Mock AuctionRepository auctionRepo;
    @Mock ParcelRepository parcelRepo;
    @Mock UserRepository userRepo;
    @Mock ParcelTagRepository tagRepo;

    @InjectMocks AuctionService service;

    private User seller;
    private Parcel parcel;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "defaultCommissionRate", new BigDecimal("0.05"));
        seller = User.builder().id(42L).email("s@example.com").verified(true).build();
        parcel = Parcel.builder().id(100L).slParcelUuid(UUID.randomUUID())
                .regionName("Coniston").continentName("Sansara").verified(true).build();
        lenient().when(userRepo.findById(42L)).thenReturn(Optional.of(seller));
        lenient().when(parcelRepo.findById(100L)).thenReturn(Optional.of(parcel));
        lenient().when(auctionRepo.save(any(Auction.class))).thenAnswer(inv -> {
            Auction a = inv.getArgument(0);
            if (a.getId() == null) a.setId(1L);
            return a;
        });
    }

    // -------------------------------------------------------------------------
    // create(): happy path
    // -------------------------------------------------------------------------

    @Test
    void create_validRequest_savesInDraft() {
        AuctionCreateRequest req = new AuctionCreateRequest(
                100L, VerificationMethod.UUID_ENTRY, 1000L, null, null,
                168, false, null, "Nice parcel", Set.of());

        Auction a = service.create(42L, req);

        assertThat(a.getStatus()).isEqualTo(AuctionStatus.DRAFT);
        assertThat(a.getSeller().getId()).isEqualTo(42L);
        assertThat(a.getParcel().getId()).isEqualTo(100L);
        assertThat(a.getListingFeePaid()).isFalse();
        assertThat(a.getCommissionRate()).isEqualByComparingTo(new BigDecimal("0.05"));
    }

    @Test
    void create_withSnipeProtect_setsSnipeWindow() {
        AuctionCreateRequest req = new AuctionCreateRequest(
                100L, VerificationMethod.UUID_ENTRY, 1000L, null, null,
                168, true, 10, null, Set.of());

        Auction a = service.create(42L, req);

        assertThat(a.getSnipeProtect()).isTrue();
        assertThat(a.getSnipeWindowMin()).isEqualTo(10);
    }

    // -------------------------------------------------------------------------
    // create(): pricing validation
    // -------------------------------------------------------------------------

    @Test
    void create_reservePriceLessThanStarting_throws() {
        AuctionCreateRequest req = new AuctionCreateRequest(
                100L, VerificationMethod.UUID_ENTRY, 1000L, 500L, null,
                168, false, null, null, Set.of());

        assertThatThrownBy(() -> service.create(42L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reservePrice must be >= startingBid");
    }

    @Test
    void create_buyNowLessThanReserve_throws() {
        AuctionCreateRequest req = new AuctionCreateRequest(
                100L, VerificationMethod.UUID_ENTRY, 1000L, 5000L, 4000L,
                168, false, null, null, Set.of());

        assertThatThrownBy(() -> service.create(42L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("buyNowPrice must be >= max");
    }

    @Test
    void create_buyNowLessThanStartingWithNoReserve_throws() {
        AuctionCreateRequest req = new AuctionCreateRequest(
                100L, VerificationMethod.UUID_ENTRY, 1000L, null, 500L,
                168, false, null, null, Set.of());

        assertThatThrownBy(() -> service.create(42L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("buyNowPrice must be >= max");
    }

    // -------------------------------------------------------------------------
    // create(): duration validation
    // -------------------------------------------------------------------------

    @Test
    void create_durationNotInAllowedSet_throws() {
        AuctionCreateRequest req = new AuctionCreateRequest(
                100L, VerificationMethod.UUID_ENTRY, 1000L, null, null,
                100, false, null, null, Set.of());

        assertThatThrownBy(() -> service.create(42L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durationHours");
    }

    // -------------------------------------------------------------------------
    // create(): snipe validation
    // -------------------------------------------------------------------------

    @Test
    void create_snipeProtectTrueWithNullWindow_throws() {
        AuctionCreateRequest req = new AuctionCreateRequest(
                100L, VerificationMethod.UUID_ENTRY, 1000L, null, null,
                168, true, null, null, Set.of());

        assertThatThrownBy(() -> service.create(42L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("snipeWindowMin");
    }

    @Test
    void create_snipeProtectFalseWithWindow_throws() {
        AuctionCreateRequest req = new AuctionCreateRequest(
                100L, VerificationMethod.UUID_ENTRY, 1000L, null, null,
                168, false, 10, null, Set.of());

        assertThatThrownBy(() -> service.create(42L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("snipeWindowMin must be null");
    }

    @Test
    void create_snipeWindowNotInAllowedSet_throws() {
        AuctionCreateRequest req = new AuctionCreateRequest(
                100L, VerificationMethod.UUID_ENTRY, 1000L, null, null,
                168, true, 7, null, Set.of());

        assertThatThrownBy(() -> service.create(42L, req))
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
                100L, VerificationMethod.UUID_ENTRY, 1000L, null, null,
                168, false, null, null, codes);

        assertThatThrownBy(() -> service.create(42L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown parcel tag codes")
                .hasMessageContaining("unknown_tag");
    }

    @Test
    void create_parcelNotFound_throws() {
        when(parcelRepo.findById(999L)).thenReturn(Optional.empty());
        AuctionCreateRequest req = new AuctionCreateRequest(
                999L, VerificationMethod.UUID_ENTRY, 1000L, null, null,
                168, false, null, null, Set.of());

        assertThatThrownBy(() -> service.create(42L, req))
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
                null, 2000L, null, null, null, null, null, "updated desc", null);
        Auction updated = service.update(1L, 42L, req);

        assertThat(updated.getStartingBid()).isEqualTo(2000L);
        assertThat(updated.getSellerDesc()).isEqualTo("updated desc");
    }

    @Test
    void update_draftPaidStatus_succeeds() {
        Auction existing = buildAuction(AuctionStatus.DRAFT_PAID);
        when(auctionRepo.findByIdAndSellerId(1L, 42L)).thenReturn(Optional.of(existing));

        AuctionUpdateRequest req = new AuctionUpdateRequest(
                null, 2000L, null, null, null, null, null, null, null);
        Auction updated = service.update(1L, 42L, req);

        assertThat(updated.getStartingBid()).isEqualTo(2000L);
    }

    @Test
    void update_verificationPendingStatus_throwsInvalidState() {
        Auction existing = buildAuction(AuctionStatus.VERIFICATION_PENDING);
        when(auctionRepo.findByIdAndSellerId(1L, 42L)).thenReturn(Optional.of(existing));

        AuctionUpdateRequest req = new AuctionUpdateRequest(
                null, 2000L, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.update(1L, 42L, req))
                .isInstanceOf(InvalidAuctionStateException.class);
    }

    @Test
    void update_activeStatus_throwsInvalidState() {
        Auction existing = buildAuction(AuctionStatus.ACTIVE);
        when(auctionRepo.findByIdAndSellerId(1L, 42L)).thenReturn(Optional.of(existing));

        AuctionUpdateRequest req = new AuctionUpdateRequest(
                null, 2000L, null, null, null, null, null, null, null);

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
                null, null, null, null, null, false, null, null, null);
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
        return Auction.builder()
                .id(1L).seller(seller).parcel(parcel).status(status)
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
    }
}
