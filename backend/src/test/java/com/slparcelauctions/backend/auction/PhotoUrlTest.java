package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Locks the single source of truth for photo-byte URLs. The serving
 * endpoint is {@code GET /api/v1/photos/{publicId}} in
 * {@link PhotoController}, whose path variable is a {@code UUID} — a
 * numeric DB id produces a 404 (Spring cannot parse "42" as a UUID) and a
 * broken {@code <img>}. The helper's {@code UUID} parameter makes passing
 * a {@code Long} a compile error; these tests pin the exact string shape,
 * the primary-photo selection, and the null semantics.
 */
class PhotoUrlTest {

    @Test
    void forPhoto_buildsFlatPhotoPath() {
        UUID publicId = UUID.fromString("11111111-2222-3333-4444-555555555555");

        assertThat(PhotoUrl.forPhoto(publicId))
                .isEqualTo("/api/v1/photos/11111111-2222-3333-4444-555555555555");
    }

    @Test
    void forPhoto_producedSegmentParsesAsUuidNotNumericId() {
        UUID publicId = UUID.randomUUID();

        String url = PhotoUrl.forPhoto(publicId);
        // /api/v1/photos/<segment> -> segment must be a UUID.
        String idSegment = url.substring("/api/v1/photos/".length());

        assertThat(UUID.fromString(idSegment)).isEqualTo(publicId);
        assertThat(url).doesNotContain("/auctions/");
        assertThat(url).doesNotContain("/bytes");
    }

    @Test
    void forPhotoOrNull_returnsNullForNullPublicId() {
        assertThat(PhotoUrl.forPhotoOrNull(null)).isNull();
    }

    @Test
    void forPhotoOrNull_buildsUrlForNonNullPublicId() {
        UUID publicId = UUID.randomUUID();

        assertThat(PhotoUrl.forPhotoOrNull(publicId))
                .isEqualTo("/api/v1/photos/" + publicId);
    }

    @Test
    void primaryForAuction_returnsFirstPhotoBySortOrder() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        AuctionPhoto p2 = AuctionPhoto.builder().publicId(second).sortOrder(2).build();
        AuctionPhoto p0 = AuctionPhoto.builder().publicId(first).sortOrder(0).build();

        Auction auction = Auction.builder()
                .title("Lakefront")
                .slParcelUuid(UUID.randomUUID())
                // Intentionally out of order — helper must sort by sortOrder.
                .photos(List.of(p2, p0))
                .build();

        assertThat(PhotoUrl.primaryForAuction(auction))
                .isEqualTo("/api/v1/photos/" + first);
    }

    @Test
    void primaryForAuction_emptyPhotos_returnsNull() {
        Auction auction = Auction.builder()
                .title("Lakefront")
                .slParcelUuid(UUID.randomUUID())
                .photos(List.of())
                .build();

        assertThat(PhotoUrl.primaryForAuction(auction)).isNull();
    }
}
