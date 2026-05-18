package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Keeps the "review thumbnail points at a nonexistent route / numeric DB
 * id" bug class dead.
 *
 * <p>The strongest guard is structural: {@link PhotoUrl} takes a
 * {@code UUID}, so handing it a {@code Long} DB id is a <em>compile</em>
 * error and no caller can reintroduce the numeric-id bug through the
 * helper. This test adds source-level guards so a future caller cannot
 * re-introduce a hand-rolled photo URL <em>around</em> the helper:
 *
 * <ol>
 *   <li>nobody hand-rolls the legacy-broken
 *       {@code "/api/v1/auctions/" + ... + "/photos/" + ... + "/bytes"}
 *       shape (a route that never existed); and</li>
 *   <li>nobody hand-rolls a {@code "/api/v1/photos/" + ...} Java
 *       concatenation outside {@link PhotoUrl} — the helper must stay the
 *       single Java producer.</li>
 * </ol>
 *
 * <p>The native-SQL projection in {@code AuctionPhotoBatchRepositoryImpl}
 * ({@code '/api/v1/photos/' || public_id}) is intentionally NOT matched:
 * it is a SQL {@code ||} concat (not a Java {@code +} expression), it
 * already emits the UUID {@code public_id}, and a Java helper cannot run
 * inside a SQL string. The regexes bind to the Java {@code "..." +}
 * shape so that site is correctly out of scope.
 */
class PhotoUrlGuardTest {

    private static final Path MAIN_ROOT =
            Path.of("src/main/java/com/slparcelauctions/backend");

    @Test
    void noFileHandRollsTheLegacyBrokenAuctionPhotosBytesPath() throws IOException {
        assertThat(Files.isDirectory(MAIN_ROOT))
                .as("backend main source root must exist for the guard scan")
                .isTrue();

        // The legacy bug shape, exactly:
        //   "/api/v1/auctions/" + <expr> + "/photos/" + <expr> + "/bytes"
        // DOTALL so the concatenation may wrap across lines. No ';' between
        // the literals keeps the match inside a single statement so an
        // unrelated later literal can't be stitched onto an earlier one.
        Pattern legacyBroken = Pattern.compile(
                "\"/api/v1/auctions/\"\\s*\\+[^;]*?\"/bytes\"", Pattern.DOTALL);

        assertThat(offenders(legacyBroken, "PhotoUrl.java"))
                .as("the /api/v1/auctions/{id}/photos/{id}/bytes route does "
                        + "not exist — build photo URLs via PhotoUrl")
                .isEmpty();
    }

    @Test
    void noFileHandRollsTheFlatPhotosPathOutsidePhotoUrl() throws IOException {
        assertThat(Files.isDirectory(MAIN_ROOT)).isTrue();

        // A hand-rolled Java concatenation of the flat photo path:
        //   "/api/v1/photos/" + <expr>
        // The ONLY legal occurrence is inside PhotoUrl itself. The SQL
        // projection uses '||' not '+' (and single quotes), so it does not
        // match. PhotoController's @GetMapping("/{publicId}") is a mapping
        // annotation, not a "/api/v1/photos/" + concat, so it does not
        // match either — only PhotoUrl.java is excluded.
        Pattern handRolledFlat = Pattern.compile(
                "\"/api/v1/photos/\"\\s*\\+", Pattern.DOTALL);

        assertThat(offenders(handRolledFlat, "PhotoUrl.java"))
                .as("photo URLs must be built via PhotoUrl, not hand-rolled")
                .isEmpty();
    }

    private List<String> offenders(Pattern pattern, String excludedFileName)
            throws IOException {
        try (Stream<Path> files = Files.walk(MAIN_ROOT)) {
            return files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals(excludedFileName))
                    .filter(p -> {
                        try {
                            return pattern.matcher(Files.readString(p)).find();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .map(Path::toString)
                    .toList();
        }
    }
}
