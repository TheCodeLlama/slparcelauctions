package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.region.dto.RegionPageData;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.dto.ParcelPageData;
import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import reactor.core.publisher.Mono;

/**
 * Full-stack coverage for the auction photo endpoints. Writes land in the
 * real dev MinIO bucket; {@link #cleanup()} removes any objects uploaded by
 * each test so reruns don't leak objects.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.realty.invitation-expiry.enabled=false"
})
@Transactional
class AuctionPhotoControllerIntegrationTest {

    private static final String TRUSTED_OWNER = "00000000-0000-0000-0000-000000000001";
    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AuctionRepository auctionRepository;
    @Autowired AuctionPhotoRepository photoRepository;
    @Autowired ObjectStorageService storage;

    @MockitoBean SlWorldApiClient worldApi;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<Long> createdAuctionIds = new ArrayList<>();

    private String sellerAccessToken;
    private Long sellerId;
    private UUID sellerParcelUuid;

    @BeforeEach
    void setup() throws Exception {
        String uniqueEmail = "photo-seller-" + UUID.randomUUID() + "@example.com";
        sellerAccessToken = registerAndVerify(uniqueEmail, "PhotoSeller",
                UUID.randomUUID().toString());
        sellerId = userRepository.findByUsername(uniqueEmail).orElseThrow().getId();
        sellerParcelUuid = seedParcel();
    }

    @AfterEach
    void cleanup() {
        for (Long id : createdAuctionIds) {
            try {
                storage.deletePrefix("listings/" + id + "/");
            } catch (Exception ignored) {
                // best-effort; primary assertions already passed/failed
            }
            try {
                photoRepository.findByAuctionIdOrderBySortOrderAsc(id)
                        .forEach(photoRepository::delete);
                auctionRepository.findById(id).ifPresent(auctionRepository::delete);
            } catch (Exception ignored) {
                // same rationale as above
            }
        }
        createdAuctionIds.clear();
    }

    @Test
    void upload_validPng_returns201AndPersistsRow() throws Exception {
        Auction a = seedDraftAuction();
        byte[] bytes = Files.readAllBytes(FIXTURES.resolve("avatar-valid.png"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", bytes);

        MvcResult res = mockMvc.perform(multipart("/api/v1/auctions/" + a.getPublicId() + "/photos")
                .file(file)
                .header("Authorization", "Bearer " + sellerAccessToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.publicId").exists())
                // Post-chokepoint migration every raster lands as WebP.
                .andExpect(jsonPath("$.contentType").value("image/webp"))
                .andExpect(jsonPath("$.sortOrder").value(1))
                .andReturn();

        UUID photoPublicId = UUID.fromString(objectMapper.readTree(res.getResponse().getContentAsString())
                .get("publicId").asText());
        AuctionPhoto saved = photoRepository.findByPublicId(photoPublicId).orElseThrow();
        assertThat(saved.getObjectKey()).startsWith("listings/" + a.getId() + "/");
        assertThat(saved.getObjectKey()).endsWith(".webp");
    }

    @Test
    void upload_bmpFile_returns400() throws Exception {
        Auction a = seedDraftAuction();
        byte[] bytes = Files.readAllBytes(FIXTURES.resolve("avatar-invalid.bmp"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.bmp", "image/bmp", bytes);

        mockMvc.perform(multipart("/api/v1/auctions/" + a.getPublicId() + "/photos")
                .file(file)
                .header("Authorization", "Bearer " + sellerAccessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LISTING_PHOTO_INVALID"));
    }

    @Test
    void upload_oversizedBytes_returns413() throws Exception {
        Auction a = seedDraftAuction();
        // Spring's multipart cap is 25 MB (matching slpa.photos.max-bytes). To
        // exercise the 413 path we synthesise an in-memory byte array just over
        // the limit â€” generating a real 25MB image fixture is wasteful and
        // image content is irrelevant since the multipart layer rejects before
        // any decode happens.
        byte[] bytes = new byte[26 * 1024 * 1024];

        MockMultipartFile file = new MockMultipartFile(
                "file", "big.png", "image/png", bytes);

        mockMvc.perform(multipart("/api/v1/auctions/" + a.getPublicId() + "/photos")
                .file(file)
                .header("Authorization", "Bearer " + sellerAccessToken))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void upload_exceedsPerListingLimit_returns413() throws Exception {
        Auction a = seedDraftAuction();
        // Prefill 10 photo rows directly (bypassing validator/storage) to set up the limit.
        for (int i = 1; i <= 10; i++) {
            photoRepository.save(AuctionPhoto.builder()
                    .auction(a)
                    .objectKey("listings/" + a.getId() + "/stub-" + i + ".png")
                    .contentType("image/png")
                    .sizeBytes(1L)
                    .sortOrder(i)
                    .build());
        }
        byte[] bytes = Files.readAllBytes(FIXTURES.resolve("avatar-valid.png"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", bytes);

        mockMvc.perform(multipart("/api/v1/auctions/" + a.getPublicId() + "/photos")
                .file(file)
                .header("Authorization", "Bearer " + sellerAccessToken))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PHOTO_LIMIT_EXCEEDED"));
    }

    @Test
    void delete_existingPhoto_returns204AndRemovesObject() throws Exception {
        Auction a = seedDraftAuction();
        byte[] bytes = Files.readAllBytes(FIXTURES.resolve("avatar-valid.png"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", bytes);
        MvcResult upload = mockMvc.perform(multipart("/api/v1/auctions/" + a.getPublicId() + "/photos")
                .file(file)
                .header("Authorization", "Bearer " + sellerAccessToken))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(upload.getResponse().getContentAsString());
        UUID photoPublicId = UUID.fromString(body.get("publicId").asText());
        AuctionPhoto photo = photoRepository.findByPublicId(photoPublicId).orElseThrow();

        mockMvc.perform(delete("/api/v1/auctions/" + a.getPublicId() + "/photos/" + photoPublicId)
                .header("Authorization", "Bearer " + sellerAccessToken))
                .andExpect(status().isNoContent());

        assertThat(photoRepository.findByPublicId(photoPublicId)).isEmpty();
        assertThat(storage.exists(photo.getObjectKey())).isFalse();
    }

    @Test
    void getBytes_activeAuction_publiclyServesPhotoWithCacheHeader() throws Exception {
        Auction a = seedDraftAuction();
        byte[] bytes = generateSimplePng();
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", bytes);
        MvcResult upload = mockMvc.perform(multipart("/api/v1/auctions/" + a.getPublicId() + "/photos")
                .file(file)
                .header("Authorization", "Bearer " + sellerAccessToken))
                .andExpect(status().isCreated())
                .andReturn();
        UUID photoPublicId = UUID.fromString(objectMapper.readTree(upload.getResponse().getContentAsString())
                .get("publicId").asText());

        a.setStatus(AuctionStatus.ACTIVE);
        auctionRepository.save(a);

        mockMvc.perform(get("/api/v1/photos/" + photoPublicId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/webp"))
                .andExpect(header().string("Cache-Control", "public, max-age=86400"));
    }

    @Test
    void getBytes_draftAuction_servesBytesToAnonymous() throws Exception {
        // Photo bytes are fully public on every status â€” `<img src>` cannot send
        // the seller's JWT, so the homepage and listing cards must be able to
        // fetch draft snapshots anonymously. The auction's metadata stays
        // hidden behind AuctionController's pre-ACTIVE 404 logic.
        Auction a = seedDraftAuction();
        byte[] bytes = generateSimplePng();
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", bytes);
        MvcResult upload = mockMvc.perform(multipart("/api/v1/auctions/" + a.getPublicId() + "/photos")
                .file(file)
                .header("Authorization", "Bearer " + sellerAccessToken))
                .andExpect(status().isCreated())
                .andReturn();
        UUID photoPublicId = UUID.fromString(objectMapper.readTree(upload.getResponse().getContentAsString())
                .get("publicId").asText());

        mockMvc.perform(get("/api/v1/photos/" + photoPublicId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/webp"));
    }

    @Test
    void reorder_happyPath_returns200WithReorderedDtoArray() throws Exception {
        Auction a = seedDraftAuction();
        // Seed 3 photo rows directly so we don't have to upload + read back IDs.
        java.util.List<UUID> publicIds = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            AuctionPhoto p = photoRepository.save(AuctionPhoto.builder()
                    .auction(a).objectKey("listings/" + a.getId() + "/seed-" + i + ".webp")
                    .contentType("image/webp").sizeBytes(1L).sortOrder(i).build());
            publicIds.add(p.getPublicId());
        }
        java.util.List<UUID> reverseOrder = java.util.List.of(
                publicIds.get(2), publicIds.get(1), publicIds.get(0));

        String body = objectMapper.writeValueAsString(
                java.util.Map.of("photoPublicIds", reverseOrder));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/auctions/" + a.getPublicId() + "/photos/order")
                        .header("Authorization", "Bearer " + sellerAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].publicId").value(reverseOrder.get(0).toString()))
                .andExpect(jsonPath("$[0].sortOrder").value(1))
                .andExpect(jsonPath("$[2].publicId").value(reverseOrder.get(2).toString()))
                .andExpect(jsonPath("$[2].sortOrder").value(3));
    }

    @Test
    void reorder_setMismatch_returns400WithCode() throws Exception {
        Auction a = seedDraftAuction();
        java.util.List<UUID> publicIds = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            AuctionPhoto p = photoRepository.save(AuctionPhoto.builder()
                    .auction(a).objectKey("listings/" + a.getId() + "/seed-" + i + ".webp")
                    .contentType("image/webp").sizeBytes(1L).sortOrder(i).build());
            publicIds.add(p.getPublicId());
        }
        java.util.List<UUID> withStray = java.util.List.of(
                publicIds.get(0), publicIds.get(1), UUID.randomUUID());

        String body = objectMapper.writeValueAsString(
                java.util.Map.of("photoPublicIds", withStray));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/auctions/" + a.getPublicId() + "/photos/order")
                        .header("Authorization", "Bearer " + sellerAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PHOTO_SET_MISMATCH"));
    }

    @Test
    void reorder_activeAuction_returns409() throws Exception {
        Auction a = seedDraftAuction();
        AuctionPhoto p = photoRepository.save(AuctionPhoto.builder()
                .auction(a).objectKey("listings/" + a.getId() + "/seed-1.webp")
                .contentType("image/webp").sizeBytes(1L).sortOrder(1).build());
        a.setStatus(AuctionStatus.ACTIVE);
        auctionRepository.save(a);

        String body = objectMapper.writeValueAsString(
                java.util.Map.of("photoPublicIds", java.util.List.of(p.getPublicId())));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/auctions/" + a.getPublicId() + "/photos/order")
                        .header("Authorization", "Bearer " + sellerAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUCTION_INVALID_STATE"));
    }

    @Test
    void reorder_anonymous_returns401() throws Exception {
        Auction a = seedDraftAuction();
        AuctionPhoto p = photoRepository.save(AuctionPhoto.builder()
                .auction(a).objectKey("listings/" + a.getId() + "/seed-1.webp")
                .contentType("image/webp").sizeBytes(1L).sortOrder(1).build());

        String body = objectMapper.writeValueAsString(
                java.util.Map.of("photoPublicIds", java.util.List.of(p.getPublicId())));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/auctions/" + a.getPublicId() + "/photos/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getBytes_draftAuction_servesBytesToSeller() throws Exception {
        Auction a = seedDraftAuction();
        byte[] bytes = generateSimplePng();
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", bytes);
        MvcResult upload = mockMvc.perform(multipart("/api/v1/auctions/" + a.getPublicId() + "/photos")
                .file(file)
                .header("Authorization", "Bearer " + sellerAccessToken))
                .andExpect(status().isCreated())
                .andReturn();
        UUID photoPublicId = UUID.fromString(objectMapper.readTree(upload.getResponse().getContentAsString())
                .get("publicId").asText());

        mockMvc.perform(get("/api/v1/photos/" + photoPublicId)
                .header("Authorization", "Bearer " + sellerAccessToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/webp"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static byte[] generateSimplePng() throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private String registerUser(String email, String displayName) throws Exception {
        String body = String.format(
                "{\"username\":\"%s\",\"password\":\"hunter22abc\"}",
                email, displayName);
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(reg.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private String registerAndVerify(String email, String displayName, String avatarUuid)
            throws Exception {
        String token = registerUser(email, displayName);
        MvcResult gen = mockMvc.perform(post("/api/v1/verification/generate")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        String code = objectMapper.readTree(gen.getResponse().getContentAsString())
                .get("code").asText();
        String body = String.format("""
            {
              "verificationCode":"%s",
              "avatarUuid":"%s",
              "avatarName":"%s",
              "displayName":"%s",
              "username":"photo.resident",
              "bornDate":"2012-05-15",
              "payInfo":3
            }
            """, code, avatarUuid, displayName, displayName);
        mockMvc.perform(post("/api/v1/sl/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-SecondLife-Shard", "Production")
                .header("X-SecondLife-Owner-Key", TRUSTED_OWNER))
                .andExpect(status().isOk());
        return token;
    }

    private UUID seedParcel() throws Exception {
        UUID regionUuid = UUID.randomUUID();
        UUID parcelUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        when(worldApi.fetchParcelPage(parcelUuid)).thenReturn(
                Mono.just(new ParcelPageData(new ParcelMetadata(
                        parcelUuid,
                ownerUuid,
                "agent",
                null,
                "Seed Parcel",
                "Coniston",
                1024,
                "Seed description",
                "http://example.com/snap.jpg",
                null,
                128.0,
                64.0,
                22.0), regionUuid)));
        when(worldApi.fetchRegionPage(regionUuid)).thenReturn(
                Mono.just(new RegionPageData(regionUuid, "Coniston", 1014.0, 1014.0, "M_NOT")));
        return parcelUuid;
    }

    private Auction seedDraftAuction() {
        User seller = userRepository.findById(sellerId).orElseThrow();
        Auction a = Auction.builder()
                .title("Test listing")
                .slParcelUuid(sellerParcelUuid)
                .seller(seller)
                .status(AuctionStatus.DRAFT)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .verificationTier(VerificationTier.SCRIPT)
                .startingBid(1000L)
                .durationHours(168)
                .snipeProtect(false)
                .listingFeePaid(false)
                .currentBid(0L)
                .bidCount(0)
                .commissionRate(new BigDecimal("0.05"))
                .consecutiveWorldApiFailures(0)
                .build();
        Auction saved = auctionRepository.save(a);
        saved.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(sellerParcelUuid)
                .ownerUuid(seller.getSlAvatarUuid())
                .ownerType("agent")
                .parcelName("Seed Parcel")
                .regionName("Coniston")
                .regionMaturityRating("M_NOT")
                .areaSqm(1024)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
        saved = auctionRepository.save(saved);
        createdAuctionIds.add(saved.getId());
        return saved;
    }
}
