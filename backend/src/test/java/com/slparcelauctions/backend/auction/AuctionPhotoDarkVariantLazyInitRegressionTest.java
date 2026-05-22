package com.slparcelauctions.backend.auction;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Regression coverage for the {@link org.hibernate.LazyInitializationException}
 * class of bug that surfaced on {@code GET /api/v1/admin/coupons/{publicId}}
 * (PR #388). Plan Task 7 of the theme-image-variants feature adds a dark
 * variant slot on the auction's sort-0 default-cover row; the public auction
 * detail mapper walks the photo list at mapping time and reads each row's
 * {@code light_object_key} + {@code dark_object_key} columns to emit the
 * {@code lightUrl} + {@code darkUrl} pair on the {@code AuctionPhotoResponse}.
 *
 * <p>Pattern matches {@link com.slparcelauctions.backend.realty.controller.RealtyGroupDefaultListingImageLazyInitRegressionTest}:
 * non-{@code @Transactional} test class, {@code @Transactional} seed +
 * cleanup so the controller's call survives a closed session and we still
 * scrub fixtures between runs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.auction-end.enabled=false",
        "slpa.ownership-monitor.enabled=false",
        "slpa.escrow.ownership-monitor-job.enabled=false",
        "slpa.escrow.timeout-job.enabled=false",
        "slpa.escrow.command-dispatcher-job.enabled=false",
        "slpa.review.scheduler.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false",
        "slpa.realty.invitation-expiry.enabled=false"
})
class AuctionPhotoDarkVariantLazyInitRegressionTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired AuctionPhotoRepository photoRepo;

    // The GET we exercise hits the JSON DTO endpoint - it does not request
    // bytes - so the mock never receives a get() call. Seeding the
    // *ObjectKey columns is enough to drive the mapper's URL emission.
    @MockitoBean ObjectStorageService storage;

    private Long sellerId;
    private Long auctionId;
    private UUID auctionPublicId;

    @BeforeEach
    @Transactional
    void seed() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User seller = userRepo.save(User.builder()
                .username("dark-lazy-" + suffix)
                .email("dark-lazy-" + suffix + "@example.com")
                .passwordHash("x")
                .displayName("LazyInit Seller")
                .build());
        sellerId = seller.getId();

        Auction a = auctionRepo.save(Auction.builder()
                .title("LazyInit Auction " + suffix)
                .slParcelUuid(UUID.randomUUID())
                .seller(seller)
                .status(AuctionStatus.ACTIVE)
                .verificationTier(VerificationTier.SCRIPT)
                .startingBid(1000L)
                .durationHours(168)
                .endsAt(OffsetDateTime.now().plusDays(7))
                .snipeProtect(false)
                .listingFeePaid(true)
                .currentBid(0L)
                .bidCount(0)
                .commissionRate(new BigDecimal("0.05"))
                .consecutiveWorldApiFailures(0)
                .build());
        a.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(a.getSlParcelUuid())
                .ownerUuid(UUID.randomUUID())
                .ownerType("agent")
                .parcelName("LazyInit Parcel")
                .regionName("LazyInit Region")
                .regionMaturityRating("M_NOT")
                .areaSqm(1024)
                .positionX(0.0).positionY(0.0).positionZ(0.0)
                .build());
        a = auctionRepo.save(a);
        auctionId = a.getId();
        auctionPublicId = a.getPublicId();

        // Seed the sort-0 default-cover row carrying BOTH variants. The
        // *ObjectKey columns are the inputs to the URL mapper; the bytes do
        // not need to exist for the mapper to emit non-null URLs.
        photoRepo.save(AuctionPhoto.builder()
                .auction(a)
                .lightObjectKey("listings/" + a.getId() + "/cover-light.webp")
                .lightContentType("image/webp")
                .lightSizeBytes(2048L)
                .darkObjectKey("listings/" + a.getId() + "/cover-dark.webp")
                .darkContentType("image/webp")
                .darkSizeBytes(2048L)
                .sortOrder(0)
                .source(PhotoSource.USER_DEFAULT_COVER)
                .build());
    }

    @AfterEach
    @Transactional
    void cleanup() {
        if (auctionId != null) {
            photoRepo.findByAuctionIdOrderBySortOrderAsc(auctionId)
                    .forEach(photoRepo::delete);
            auctionRepo.findById(auctionId).ifPresent(auctionRepo::delete);
        }
        if (sellerId != null) {
            userRepo.findById(sellerId).ifPresent(userRepo::delete);
        }
    }

    @Test
    void getPublicAuction_outsideEnclosingTx_returnsBothPhotoUrls() throws Exception {
        // Without @Transactional(readOnly=true) on AuctionController.get, the
        // mapper's walk of a.getTags() / a.getSeller() / photoRepo lookups
        // would trip LazyInitializationException on a real HTTP request. The
        // controller already declares the read-only tx; this test guards that
        // the new dark URL emission survives that closed-session boundary
        // alongside the existing light URL emission.
        mockMvc.perform(get("/api/v1/auctions/" + auctionPublicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(auctionPublicId.toString()))
                .andExpect(jsonPath("$.photos[0].lightUrl").value(
                        org.hamcrest.Matchers.endsWith("?variant=light")))
                .andExpect(jsonPath("$.photos[0].darkUrl").value(
                        org.hamcrest.Matchers.endsWith("?variant=dark")))
                .andExpect(jsonPath("$.photos[0].source").value("USER_DEFAULT_COVER"));
    }
}
