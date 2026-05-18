package com.slparcelauctions.backend.review.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionPhoto;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.user.User;

/**
 * Regression for the same avatar-URL class of bug on the dashboard's
 * "reviews waiting for you" card: {@link PendingReviewDto#of} built the
 * counterparty avatar URL from the numeric DB {@code id} instead of the
 * UUID {@code publicId}, 404'ing the {@code GET /api/v1/users/{publicId}/
 * avatar/{size}} endpoint.
 */
class PendingReviewDtoTest {

    private User user(long id, UUID publicId, String displayName) {
        return User.builder()
                .id(id)
                .publicId(publicId)
                .email(displayName + "@example.com")
                .username(displayName.toLowerCase())
                .passwordHash("x")
                .displayName(displayName)
                .build();
    }

    @Test
    void of_counterpartyAvatarUrl_usesPublicIdNotNumericId() {
        UUID counterpartyPublicId = UUID.randomUUID();
        User seller = user(10L, UUID.randomUUID(), "Sally");
        User counterparty = user(99L, counterpartyPublicId, "Wally");
        OffsetDateTime now = OffsetDateTime.of(2026, 5, 1, 10, 0, 0, 0, ZoneOffset.UTC);

        Auction auction = Auction.builder()
                .title("Lakefront")
                .seller(seller)
                .slParcelUuid(UUID.randomUUID())
                .photos(List.of())
                .build();
        Escrow escrow = Escrow.builder()
                .auction(auction)
                .state(EscrowState.COMPLETED)
                .completedAt(now.minusDays(1))
                .finalBidAmount(1_000L)
                .commissionAmt(50L)
                .payoutAmt(950L)
                .build();

        PendingReviewDto dto = PendingReviewDto.of(escrow, seller, counterparty, now);

        assertThat(dto.counterpartyAvatarUrl())
                .isEqualTo("/api/v1/users/" + counterpartyPublicId + "/avatar/256");
        assertThat(dto.counterpartyAvatarUrl()).doesNotContain("/users/99/");

        String url = dto.counterpartyAvatarUrl();
        String idSegment = url.substring(
                "/api/v1/users/".length(), url.indexOf("/avatar/"));
        assertThat(UUID.fromString(idSegment)).isEqualTo(counterpartyPublicId);
    }

    @Test
    void of_primaryPhotoUrl_pointsAtFlatPhotosEndpointWithPublicId() {
        // Regression for the prod bug: PendingReviewDto.of built the
        // thumbnail as "/api/v1/auctions/" + auction.getId() + "/photos/"
        // + photo.getId() + "/bytes" — a route that does not exist, fed
        // numeric DB ids. The dashboard pending-reviews card 404'd.
        UUID photoPublicId = UUID.randomUUID();
        long auctionNumericId = 555L;
        long photoNumericId = 4242L;

        User seller = user(10L, UUID.randomUUID(), "Sally");
        User counterparty = user(99L, UUID.randomUUID(), "Wally");
        OffsetDateTime now = OffsetDateTime.of(2026, 5, 1, 10, 0, 0, 0, ZoneOffset.UTC);

        AuctionPhoto photo = AuctionPhoto.builder()
                .publicId(photoPublicId)
                .sortOrder(0)
                .build();
        setEntityId(photo, photoNumericId);

        Auction auction = Auction.builder()
                .title("Lakefront")
                .seller(seller)
                .slParcelUuid(UUID.randomUUID())
                .photos(List.of(photo))
                .build();
        setEntityId(auction, auctionNumericId);

        Escrow escrow = Escrow.builder()
                .auction(auction)
                .state(EscrowState.COMPLETED)
                .completedAt(now.minusDays(1))
                .finalBidAmount(1_000L)
                .commissionAmt(50L)
                .payoutAmt(950L)
                .build();

        PendingReviewDto dto = PendingReviewDto.of(escrow, seller, counterparty, now);

        assertThat(dto.primaryPhotoUrl())
                .isEqualTo("/api/v1/photos/" + photoPublicId);
        // The exact prod-bug assertions — all would FAIL under old code.
        assertThat(dto.primaryPhotoUrl()).doesNotContain("/auctions/");
        assertThat(dto.primaryPhotoUrl()).doesNotContain("/bytes");
        assertThat(dto.primaryPhotoUrl())
                .doesNotContain("/photos/" + photoNumericId);
        assertThat(dto.primaryPhotoUrl())
                .doesNotContain("/" + auctionNumericId + "/");
    }

    @Test
    void of_noPhotos_yieldsNullPrimaryPhotoUrl() {
        User seller = user(10L, UUID.randomUUID(), "Sally");
        User counterparty = user(99L, UUID.randomUUID(), "Wally");
        OffsetDateTime now = OffsetDateTime.of(2026, 5, 1, 10, 0, 0, 0, ZoneOffset.UTC);

        Auction auction = Auction.builder()
                .title("Lakefront")
                .seller(seller)
                .slParcelUuid(UUID.randomUUID())
                .photos(List.of())
                .build();
        Escrow escrow = Escrow.builder()
                .auction(auction)
                .state(EscrowState.COMPLETED)
                .completedAt(now.minusDays(1))
                .finalBidAmount(1_000L)
                .commissionAmt(50L)
                .payoutAmt(950L)
                .build();

        PendingReviewDto dto = PendingReviewDto.of(escrow, seller, counterparty, now);

        assertThat(dto.primaryPhotoUrl()).isNull();
    }

    private static void setEntityId(Object entity, long id) {
        try {
            java.lang.reflect.Field f = com.slparcelauctions.backend.common
                    .BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void of_nullCounterparty_yieldsNullAvatarUrl() {
        User seller = user(10L, UUID.randomUUID(), "Sally");
        OffsetDateTime now = OffsetDateTime.of(2026, 5, 1, 10, 0, 0, 0, ZoneOffset.UTC);

        Auction auction = Auction.builder()
                .title("Lakefront")
                .seller(seller)
                .slParcelUuid(UUID.randomUUID())
                .photos(List.of())
                .build();
        Escrow escrow = Escrow.builder()
                .auction(auction)
                .state(EscrowState.COMPLETED)
                .completedAt(now.minusDays(1))
                .finalBidAmount(1_000L)
                .commissionAmt(50L)
                .payoutAmt(950L)
                .build();

        PendingReviewDto dto = PendingReviewDto.of(escrow, seller, null, now);

        assertThat(dto.counterpartyAvatarUrl()).isNull();
    }
}
