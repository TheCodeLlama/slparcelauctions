package com.slparcelauctions.backend.sl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.sl.dto.GroupPageData;

/**
 * Fixture-based parser tests for {@link SlWorldApiClient#parseGroupHtml(UUID, String)}.
 *
 * <p>The {@code group-page-slparcels.html} fixture is a live capture of
 * {@code world.secondlife.com/group/79f06955-38f4-3124-25b3-f5506c85828f}
 * (the SLParcels SL group) — proves the parser handles the real World API
 * markup. The {@code group-page-with-charter.html} fixture is a hand-crafted
 * variant with a non-empty {@code <p class="desc">} so the about-text path
 * has positive-case coverage (the live SLParcels group has no charter set).
 */
class SlWorldApiClientGroupParserTest {

    @Test
    void parsesSlparcelsFixture() {
        String html = readFixture("/sl/group-page-slparcels.html");

        GroupPageData data = SlWorldApiClient.parseGroupHtml(
                UUID.fromString("79f06955-38f4-3124-25b3-f5506c85828f"),
                html);

        assertThat(data.slGroupUuid())
                .isEqualTo(UUID.fromString("79f06955-38f4-3124-25b3-f5506c85828f"));
        assertThat(data.name()).isEqualTo("SLParcels");
        assertThat(data.founderUuid())
                .isEqualTo(UUID.fromString("aa87bc38-c175-427d-b665-02e6838963cc"));
        assertThat(data.aboutText()).isNullOrEmpty();
    }

    @Test
    void parsesCharterFixture() {
        String html = readFixture("/sl/group-page-with-charter.html");

        GroupPageData data = SlWorldApiClient.parseGroupHtml(
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                html);

        assertThat(data.slGroupUuid())
                .isEqualTo(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
        assertThat(data.name()).isEqualTo("My Group");
        assertThat(data.founderUuid())
                .isEqualTo(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
        assertThat(data.aboutText()).isEqualTo("Expected charter text");
    }

    private static String readFixture(String path) {
        try (InputStream in = SlWorldApiClientGroupParserTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Fixture not found on classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
