package com.slparcelauctions.backend.admin.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.slparcelauctions.backend.admin.audit.AdminActionRepository;
import com.slparcelauctions.backend.admin.ban.BanRepository;
import com.slparcelauctions.backend.admin.reports.ListingReportRepository;
import com.slparcelauctions.backend.admin.users.dto.AdminUserDetailDto;
import com.slparcelauctions.backend.admin.users.dto.AdminUserListingRowDto;
import com.slparcelauctions.backend.admin.users.dto.AdminUserSummaryDto;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.BidRepository;
import com.slparcelauctions.backend.auction.CancellationLogRepository;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
import com.slparcelauctions.backend.auth.RefreshTokenRepository;
import com.slparcelauctions.backend.common.PagedResponse;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.testsupport.TestRegions;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock UserRepository userRepo;
    @Mock BanRepository banRepo;
    @Mock AuctionRepository auctionRepo;
    @Mock BidRepository bidRepo;
    @Mock CancellationLogRepository cancellationLogRepo;
    @Mock ListingReportRepository listingReportRepo;
    @Mock FraudFlagRepository fraudFlagRepo;
    @Mock AdminActionRepository adminActionRepo;
    @Mock RefreshTokenRepository refreshTokenRepo;

    private static final Clock FIXED_CLOCK =
        Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC);

    private AdminUserService service;

    @BeforeEach
    void setUp() {
        service = new AdminUserService(userRepo, banRepo, auctionRepo, bidRepo,
            cancellationLogRepo, listingReportRepo, fraudFlagRepo, adminActionRepo,
            refreshTokenRepo, FIXED_CLOCK);
    }

    private User buildUser(Long id) {
        User u = User.builder()
            .email("user" + id + "@x.com")
            .passwordHash("x")
            .displayName("User" + id)
            .build();
        try {
            java.lang.reflect.Field f = User.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return u;
    }

    // -------------------------------------------------------------------------
    // search routing
    // -------------------------------------------------------------------------

    @Test
    void search_uuidInput_parsesAsUuid_passesUuidParam() {
        UUID uuid = UUID.randomUUID();
        User user = buildUser(1L);
        Page<User> page = new PageImpl<>(List.of(user));
        Pageable pageable = PageRequest.of(0, 25);

        when(userRepo.searchAdmin(isNull(), eq(uuid), eq(pageable))).thenReturn(page);

        PagedResponse<AdminUserSummaryDto> result = service.search(uuid.toString(), pageable);

        assertThat(result.totalElements()).isEqualTo(1);
        verify(userRepo).searchAdmin(isNull(), eq(uuid), eq(pageable));
    }

    @Test
    void search_substringInput_passesSearchParam_nullUuid() {
        String input = "testuser";
        User user = buildUser(2L);
        Page<User> page = new PageImpl<>(List.of(user));
        Pageable pageable = PageRequest.of(0, 25);

        when(userRepo.searchAdmin(eq(input), isNull(), eq(pageable))).thenReturn(page);

        PagedResponse<AdminUserSummaryDto> result = service.search(input, pageable);

        assertThat(result.totalElements()).isEqualTo(1);
        verify(userRepo).searchAdmin(eq(input), isNull(), eq(pageable));
    }

    @Test
    void search_emptyInput_passesNullBoth() {
        Page<User> page = new PageImpl<>(Collections.emptyList());
        Pageable pageable = PageRequest.of(0, 25);

        when(userRepo.searchAdmin(isNull(), isNull(), eq(pageable))).thenReturn(page);

        PagedResponse<AdminUserSummaryDto> result = service.search("", pageable);

        assertThat(result.totalElements()).isEqualTo(0);
        verify(userRepo).searchAdmin(isNull(), isNull(), eq(pageable));
    }

    @Test
    void search_nullInput_passesNullBoth() {
        Page<User> page = new PageImpl<>(Collections.emptyList());
        Pageable pageable = PageRequest.of(0, 25);

        when(userRepo.searchAdmin(isNull(), isNull(), eq(pageable))).thenReturn(page);

        service.search(null, pageable);

        verify(userRepo).searchAdmin(isNull(), isNull(), eq(pageable));
    }

    // -------------------------------------------------------------------------
    // detail
    // -------------------------------------------------------------------------

    @Test
    void detail_returnsDto_withNullActiveBan_whenNoSlUuid() {
        User user = buildUser(5L);
        when(userRepo.findById(5L)).thenReturn(Optional.of(user));

        AdminUserDetailDto dto = service.detail(5L);

        assertThat(dto.id()).isEqualTo(5L);
        assertThat(dto.activeBan()).isNull();
    }

    // -------------------------------------------------------------------------
    // listings tab
    // -------------------------------------------------------------------------

    @Test
    void listings_mapsAuctionToDto() {
        Parcel parcel = Parcel.builder()
                .region(com.slparcelauctions.backend.region.Region.builder()
                        .slUuid(UUID.randomUUID()).name("Test Region")
                        .gridX(1014.0).gridY(1014.0).maturityRating("GENERAL").build())
                .build();
        Auction auction = Auction.builder()
            .title("My Auction")
            .status(AuctionStatus.ACTIVE)
            .endsAt(OffsetDateTime.now(FIXED_CLOCK).plusHours(24))
            .parcel(parcel)
            .build();
        try {
            java.lang.reflect.Field f = Auction.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(auction, 10L);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Pageable pageable = PageRequest.of(0, 25);
        when(auctionRepo.findBySellerIdOrderByCreatedAtDesc(eq(5L), eq(pageable)))
            .thenReturn(new PageImpl<>(List.of(auction)));

        PagedResponse<AdminUserListingRowDto> result = service.listings(5L, pageable);

        assertThat(result.totalElements()).isEqualTo(1);
        AdminUserListingRowDto row = result.content().get(0);
        assertThat(row.auctionId()).isEqualTo(10L);
        assertThat(row.title()).isEqualTo("My Auction");
        assertThat(row.regionName()).isEqualTo("Test Region");
        assertThat(row.status()).isEqualTo(AuctionStatus.ACTIVE);
    }
}
