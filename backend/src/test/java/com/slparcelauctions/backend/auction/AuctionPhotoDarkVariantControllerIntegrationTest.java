package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Plan Task 7 of the theme-image-variants feature: end-to-end coverage for
 * {@link AuctionPhotoDarkVariantController}.
 *
 * <p>POST/DELETE happy paths exercise the
 * {@code USER_DEFAULT_COVER} and {@code GROUP_DEFAULT_COVER} sources (the
 * only two sources that accept a dark sibling) and assert the {@code darkUrl}
 * round-trips through the DTO mapper. Negative paths assert that
 * {@code SELLER_UPLOAD} and {@code SL_PARCEL_SNAPSHOT} rows are rejected
 * with {@code 400 INVALID_PHOTO_SOURCE}, that a non-seller non-admin gets
 * {@code 403}, that an unknown photo publicId 404s, and that a photo
 * pointing at a different auction than the URL's auction publicId 404s
 * (no row-existence leak).
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
@Transactional
class AuctionPhotoDarkVariantControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired AuctionPhotoRepository photoRepo;
    @Autowired JwtService jwtService;
    @Autowired com.slparcelauctions.backend.storage.ObjectStorageService storage;

    private User seller;
    private User otherUser;
    private User admin;
    private String sellerToken;
    private String otherToken;
    private String adminToken;
    private final List<Long> createdAuctionIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        seller = userRepo.save(User.builder()
                .username("dark-seller-" + suffix)
                .email("dark-seller-" + suffix + "@example.com")
                .passwordHash("x")
                .displayName("Dark Seller")
                .role(Role.USER)
                .verified(true)
                .build());
        otherUser = userRepo.save(User.builder()
                .username("dark-other-" + suffix)
                .email("dark-other-" + suffix + "@example.com")
                .passwordHash("x")
                .displayName("Dark Other")
                .role(Role.USER)
                .verified(true)
                .build());
        admin = userRepo.save(User.builder()
                .username("dark-admin-" + suffix)
                .email("dark-admin-" + suffix + "@example.com")
                .passwordHash("x")
                .displayName("Dark Admin")
                .role(Role.ADMIN)
                .verified(true)
                .build());
        sellerToken = jwtService.issueAccessToken(new AuthPrincipal(
                seller.getId(), seller.getPublicId(), seller.getUsername(), 1L, Role.USER));
        otherToken = jwtService.issueAccessToken(new AuthPrincipal(
                otherUser.getId(), otherUser.getPublicId(), otherUser.getUsername(), 1L, Role.USER));
        adminToken = jwtService.issueAccessToken(new AuthPrincipal(
                admin.getId(), admin.getPublicId(), admin.getUsername(), 1L, Role.ADMIN));
    }

    @AfterEach
    void cleanup() {
        for (Long id : createdAuctionIds) {
            try {
                storage.deletePrefix("listings/" + id + "/");
            } catch (Exception ignored) {
                // best-effort
            }
        }
        createdAuctionIds.clear();
    }

    // ---------------------------------------------------------------- POST

    @Test
    void upload_userDefaultCover_returns200WithDarkUrl() throws Exception {
        Auction a = seedAuction();
        AuctionPhoto photo = seedDefaultCoverPhoto(a, PhotoSource.USER_DEFAULT_COVER);

        MockMultipartFile file = new MockMultipartFile(
                "file", "dark.png", "image/png", generateSimplePng());

        mockMvc.perform(multipart(uploadUrl(a, photo)).file(file)
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(photo.getPublicId().toString()))
                .andExpect(jsonPath("$.source").value("USER_DEFAULT_COVER"))
                .andExpect(jsonPath("$.darkUrl").value(
                        org.hamcrest.Matchers.endsWith("?variant=dark")));

        AuctionPhoto reloaded = photoRepo.findByPublicId(photo.getPublicId()).orElseThrow();
        assertThat(reloaded.getDarkObjectKey()).startsWith(
                "listings/" + a.getId() + "/" + photo.getPublicId() + "-dark");
        assertThat(reloaded.getDarkObjectKey()).endsWith(".webp");
        assertThat(reloaded.getDarkContentType()).isEqualTo("image/webp");
        assertThat(reloaded.getDarkSizeBytes()).isPositive();
    }

    @Test
    void upload_groupDefaultCover_returns200WithDarkUrl() throws Exception {
        Auction a = seedAuction();
        AuctionPhoto photo = seedDefaultCoverPhoto(a, PhotoSource.GROUP_DEFAULT_COVER);

        MockMultipartFile file = new MockMultipartFile(
                "file", "dark.png", "image/png", generateSimplePng());

        mockMvc.perform(multipart(uploadUrl(a, photo)).file(file)
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("GROUP_DEFAULT_COVER"))
                .andExpect(jsonPath("$.darkUrl").value(
                        org.hamcrest.Matchers.endsWith("?variant=dark")));
    }

    @Test
    void upload_sellerUpload_returns400InvalidPhotoSource() throws Exception {
        Auction a = seedAuction();
        AuctionPhoto photo = seedDefaultCoverPhoto(a, PhotoSource.SELLER_UPLOAD);

        MockMultipartFile file = new MockMultipartFile(
                "file", "dark.png", "image/png", generateSimplePng());

        mockMvc.perform(multipart(uploadUrl(a, photo)).file(file)
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PHOTO_SOURCE"))
                .andExpect(jsonPath("$.source").value("SELLER_UPLOAD"));
    }

    @Test
    void upload_parcelSnapshot_returns400InvalidPhotoSource() throws Exception {
        Auction a = seedAuction();
        AuctionPhoto photo = seedDefaultCoverPhoto(a, PhotoSource.SL_PARCEL_SNAPSHOT);

        MockMultipartFile file = new MockMultipartFile(
                "file", "dark.png", "image/png", generateSimplePng());

        mockMvc.perform(multipart(uploadUrl(a, photo)).file(file)
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PHOTO_SOURCE"))
                .andExpect(jsonPath("$.source").value("SL_PARCEL_SNAPSHOT"));
    }

    @Test
    void upload_nonSellerNonAdmin_returns403() throws Exception {
        Auction a = seedAuction();
        AuctionPhoto photo = seedDefaultCoverPhoto(a, PhotoSource.USER_DEFAULT_COVER);

        MockMultipartFile file = new MockMultipartFile(
                "file", "dark.png", "image/png", generateSimplePng());

        mockMvc.perform(multipart(uploadUrl(a, photo)).file(file)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void upload_adminOnSomeoneElsesAuction_returns200() throws Exception {
        // Admins manage the dark variant on any seller's default-cover row.
        Auction a = seedAuction();
        AuctionPhoto photo = seedDefaultCoverPhoto(a, PhotoSource.USER_DEFAULT_COVER);

        MockMultipartFile file = new MockMultipartFile(
                "file", "dark.png", "image/png", generateSimplePng());

        mockMvc.perform(multipart(uploadUrl(a, photo)).file(file)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.darkUrl").value(
                        org.hamcrest.Matchers.endsWith("?variant=dark")));
    }

    @Test
    void upload_unknownPhotoPublicId_returns404() throws Exception {
        Auction a = seedAuction();
        UUID stranger = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile(
                "file", "dark.png", "image/png", generateSimplePng());

        mockMvc.perform(multipart("/api/v1/auctions/" + a.getPublicId()
                        + "/photos/" + stranger + "/dark")
                        .file(file)
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void upload_photoFromDifferentAuction_returns404() throws Exception {
        // Cross-auction tamper attempt: the URL says auction A but the photo
        // belongs to auction B. We 404 to hide the row's existence under the
        // requested auction.
        Auction aOne = seedAuction();
        Auction aTwo = seedAuction();
        AuctionPhoto photo = seedDefaultCoverPhoto(aTwo, PhotoSource.USER_DEFAULT_COVER);

        MockMultipartFile file = new MockMultipartFile(
                "file", "dark.png", "image/png", generateSimplePng());

        mockMvc.perform(multipart("/api/v1/auctions/" + aOne.getPublicId()
                        + "/photos/" + photo.getPublicId() + "/dark")
                        .file(file)
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    // -------------------------------------------------------------- DELETE

    @Test
    void delete_userDefaultCoverWithDarkSet_clearsDarkColumns() throws Exception {
        Auction a = seedAuction();
        AuctionPhoto photo = seedDefaultCoverPhoto(a, PhotoSource.USER_DEFAULT_COVER);
        // Seed a dark blob so the delete has something to remove.
        String darkKey = "listings/" + a.getId() + "/" + photo.getPublicId() + "-dark.webp";
        storage.put(darkKey, generateSimplePng(), "image/webp");
        photo.setDarkObjectKey(darkKey);
        photo.setDarkContentType("image/webp");
        photo.setDarkSizeBytes(123L);
        photoRepo.save(photo);

        mockMvc.perform(delete("/api/v1/auctions/" + a.getPublicId()
                        + "/photos/" + photo.getPublicId() + "/dark")
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(photo.getPublicId().toString()))
                .andExpect(jsonPath("$.darkUrl").doesNotExist());

        AuctionPhoto reloaded = photoRepo.findByPublicId(photo.getPublicId()).orElseThrow();
        assertThat(reloaded.getDarkObjectKey()).isNull();
        assertThat(reloaded.getDarkContentType()).isNull();
        assertThat(reloaded.getDarkSizeBytes()).isNull();
    }

    @Test
    void delete_alreadyNullDark_isIdempotent200() throws Exception {
        Auction a = seedAuction();
        AuctionPhoto photo = seedDefaultCoverPhoto(a, PhotoSource.USER_DEFAULT_COVER);
        assertThat(photo.getDarkObjectKey()).isNull();

        mockMvc.perform(delete("/api/v1/auctions/" + a.getPublicId()
                        + "/photos/" + photo.getPublicId() + "/dark")
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.darkUrl").doesNotExist());
    }

    @Test
    void delete_sellerUpload_returns400InvalidPhotoSource() throws Exception {
        Auction a = seedAuction();
        AuctionPhoto photo = seedDefaultCoverPhoto(a, PhotoSource.SELLER_UPLOAD);

        mockMvc.perform(delete("/api/v1/auctions/" + a.getPublicId()
                        + "/photos/" + photo.getPublicId() + "/dark")
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PHOTO_SOURCE"))
                .andExpect(jsonPath("$.source").value("SELLER_UPLOAD"));
    }

    // -------------------------------------------------------------- helpers

    private String uploadUrl(Auction a, AuctionPhoto photo) {
        return "/api/v1/auctions/" + a.getPublicId() + "/photos/" + photo.getPublicId() + "/dark";
    }

    private Auction seedAuction() {
        Auction a = auctionRepo.save(Auction.builder()
                .title("Test listing " + UUID.randomUUID())
                .slParcelUuid(UUID.randomUUID())
                .seller(seller)
                .status(AuctionStatus.DRAFT)
                .verificationTier(VerificationTier.SCRIPT)
                .startingBid(1000L)
                .durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(false)
                .currentBid(0L)
                .bidCount(0)
                .commissionRate(new BigDecimal("0.05"))
                .consecutiveWorldApiFailures(0)
                .build());
        createdAuctionIds.add(a.getId());
        return a;
    }

    private AuctionPhoto seedDefaultCoverPhoto(Auction a, PhotoSource source) {
        return photoRepo.save(AuctionPhoto.builder()
                .auction(a)
                .lightObjectKey("listings/" + a.getId() + "/seed-" + UUID.randomUUID() + ".webp")
                .lightContentType("image/webp")
                .lightSizeBytes(1024L)
                .sortOrder(0)
                .source(source)
                .build());
    }

    private static byte[] generateSimplePng() {
        try {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                    16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
