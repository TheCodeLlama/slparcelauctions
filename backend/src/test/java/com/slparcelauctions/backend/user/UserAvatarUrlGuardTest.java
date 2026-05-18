package com.slparcelauctions.backend.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.search.AuctionSearchResultDto;
import com.slparcelauctions.backend.auction.search.AuctionSearchResultMapper;

/**
 * Keeps the "numeric DB id in an avatar URL" bug class dead.
 *
 * <p>The strongest guard is structural: {@link UserAvatarUrl} takes a
 * {@code UUID}, so handing it a {@code Long} DB id is a <em>compile</em>
 * error and no new caller can reintroduce the bug through the helper.
 * This test adds two runtime guards on top:
 *
 * <ol>
 *   <li>a behavioural end-to-end check that a fixed user-facing mapper
 *       ({@link AuctionSearchResultMapper}) emits the UUID {@code publicId}
 *       and never the numeric {@code getId()}; and</li>
 *   <li>a source-level check that no DTO mapper hand-rolls the
 *       {@code "/api/v1/users/" + ... + "/avatar/"} path again instead of
 *       routing through {@link UserAvatarUrl} — the helper must stay the
 *       single producer.</li>
 * </ol>
 */
class UserAvatarUrlGuardTest {

    @Test
    void fixedSearchMapper_emitsPublicIdNotNumericId() {
        long numericId = 42L;
        UUID sellerPublicId = UUID.randomUUID();
        UUID parcelUuid = UUID.randomUUID();

        User seller = User.builder()
                .id(numericId)
                .publicId(sellerPublicId)
                .email("seller@example.com")
                .username("seller")
                .passwordHash("x")
                .displayName("Sally")
                .avgSellerRating(new BigDecimal("4.5"))
                .totalSellerReviews(5)
                .build();

        Auction a = Auction.builder()
                .id(7L)
                .title("Test")
                .status(AuctionStatus.ACTIVE)
                .slParcelUuid(parcelUuid)
                .seller(seller)
                .startingBid(500L)
                .currentBid(600L)
                .bidCount(1)
                .endsAt(OffsetDateTime.now().plusDays(1))
                .snipeProtect(false)
                .verificationTier(VerificationTier.BOT)
                .durationHours(168)
                .build();
        a.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid)
                .regionName("Coniston")
                .regionMaturityRating("MODERATE")
                .areaSqm(1024)
                .positionX(80.0).positionY(104.0).positionZ(89.0)
                .build());

        AuctionSearchResultDto dto =
                new AuctionSearchResultMapper().toDto(a, Set.of(), null, null);

        String avatarUrl = dto.seller().avatarUrl();
        assertThat(avatarUrl)
                .isEqualTo("/api/v1/users/" + sellerPublicId + "/avatar/256");
        assertThat(avatarUrl).doesNotContain("/users/" + numericId + "/");

        String idSegment = avatarUrl.substring(
                "/api/v1/users/".length(), avatarUrl.indexOf("/avatar/"));
        assertThat(UUID.fromString(idSegment)).isEqualTo(sellerPublicId);
    }

    @Test
    void noDtoMapperHandRollsTheAvatarPath() throws IOException {
        Path mainRoot = Path.of("src/main/java/com/slparcelauctions/backend");
        assertThat(Files.isDirectory(mainRoot))
                .as("backend main source root must exist for the guard scan")
                .isTrue();

        // The bug shape is specifically a hand-rolled avatar path:
        //   "/api/v1/users/" + <id-expr> + "/avatar/..."
        // (DOTALL so the concatenation may wrap across lines). The only
        // legal occurrence is inside UserAvatarUrl itself. A bare
        // "/api/v1/users/" + publicId for a *different* endpoint (e.g. the
        // Location header in UserController) is intentionally NOT matched.
        // No ';' between the two literals keeps the match inside a single
        // statement, so a later unrelated "/avatar/" literal elsewhere in
        // the same file can't be stitched onto an earlier users-path.
        Pattern handRolledAvatar = Pattern.compile(
                "\"/api/v1/users/\"\\s*\\+[^;]*?\"/avatar/", Pattern.DOTALL);

        try (Stream<Path> files = Files.walk(mainRoot)) {
            List<String> offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("UserAvatarUrl.java"))
                    .filter(p -> {
                        try {
                            return handRolledAvatar.matcher(Files.readString(p)).find();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .map(Path::toString)
                    .toList();

            assertThat(offenders)
                    .as("avatar URLs must be built via UserAvatarUrl, not hand-rolled")
                    .isEmpty();
        }
    }
}
