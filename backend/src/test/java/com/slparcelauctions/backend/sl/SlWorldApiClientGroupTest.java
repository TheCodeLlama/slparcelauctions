package com.slparcelauctions.backend.sl;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.slparcelauctions.backend.sl.dto.GroupPageData;
import com.slparcelauctions.backend.sl.exception.ExternalApiTimeoutException;

class SlWorldApiClientGroupTest {

    private static WireMockServer wireMock;
    private SlWorldApiClient client;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @AfterEach
    void resetWireMock() {
        wireMock.resetAll();
    }

    private SlWorldApiClient newClient() {
        WebClient webClient = WebClient.builder().baseUrl("http://localhost:" + wireMock.port()).build();
        return new SlWorldApiClient(webClient, 3, 100);
    }

    /**
     * Fixture for {@code world.secondlife.com/group/{uuid}} that mirrors the live
     * markup shape (validated against the SLParcels group capture): a {@code .details}
     * wrapper with an unclassed {@code <h1>} for the name and a {@code <p class="desc">}
     * for the charter, plus the {@code meta[name="founderid"]} the parser prefers.
     */
    private static String fixtureHtml(String groupName, String charter, String founderUuid) {
        return """
                <html><head>
                <title>%s</title>
                <meta name="founderid" content="%s">
                </head><body>
                  <div class="details">
                    <h1>%s</h1>
                    <p class="desc">%s</p>
                    <a href="/resident/%s">Founder Resident</a>
                  </div>
                </body></html>
                """.formatted(groupName, founderUuid, groupName, charter, founderUuid);
    }

    @Test
    void fetchGroup_validHtml_parsesAllFields() {
        UUID slGroupUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID founderUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
        String html = fixtureHtml(
                "Tula Land Co-op",
                "Welcome to Tula Land Co-op. Verification: SLPA-ABCDEFGHJKMN.",
                founderUuid.toString());
        wireMock.stubFor(get(urlPathMatching("/group/.*"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html").withBody(html)));

        client = newClient();
        GroupPageData result = client.fetchGroupPage(slGroupUuid).block();

        assertThat(result).isNotNull();
        assertThat(result.slGroupUuid()).isEqualTo(slGroupUuid);
        assertThat(result.name()).isEqualTo("Tula Land Co-op");
        assertThat(result.aboutText())
                .isEqualTo("Welcome to Tula Land Co-op. Verification: SLPA-ABCDEFGHJKMN.");
        assertThat(result.founderUuid()).isEqualTo(founderUuid);
    }

    @Test
    void fetchGroup_aboutTextMissing_returnsNullAboutTextWithOtherFieldsPopulated() {
        // Empty <p class="desc"> mirrors the live SL page for a group with no
        // charter set (see group-page-slparcels.html fixture). About-text-flow
        // callers treat aboutText == null as a no-match-yet, not as an error.
        UUID slGroupUuid = UUID.randomUUID();
        UUID founderUuid = UUID.fromString("33333333-3333-3333-3333-333333333333");
        String html = """
                <html><head>
                <title>Founders-only Group</title>
                <meta name="founderid" content="%s">
                </head><body>
                  <div class="details">
                    <h1>Founders-only Group</h1>
                    <p class="desc"></p>
                    <a href="/resident/%s">Founder Resident</a>
                  </div>
                </body></html>
                """.formatted(founderUuid, founderUuid);
        wireMock.stubFor(get(urlPathMatching("/group/.*"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html").withBody(html)));

        client = newClient();
        GroupPageData result = client.fetchGroupPage(slGroupUuid).block();

        assertThat(result).isNotNull();
        assertThat(result.slGroupUuid()).isEqualTo(slGroupUuid);
        assertThat(result.name()).isEqualTo("Founders-only Group");
        assertThat(result.aboutText()).isNull();
        assertThat(result.founderUuid()).isEqualTo(founderUuid);
    }

    @Test
    void fetchGroup_detailsMissingButTitleAndResidentAnchorPresent_fallsBack() {
        // If SL ever drops the .details wrapper, the parser falls back to the
        // <title> element for the group name and the /resident/ anchor pattern
        // for the founder UUID. aboutText stays null (no .details p.desc).
        UUID slGroupUuid = UUID.randomUUID();
        UUID founderUuid = UUID.fromString("44444444-4444-4444-4444-444444444444");
        String html = """
                <html><head>
                <title>Title-only Group</title>
                </head><body>
                  <a href="/resident/%s">Founder Resident</a>
                </body></html>
                """.formatted(founderUuid);
        wireMock.stubFor(get(urlPathMatching("/group/.*"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html").withBody(html)));

        client = newClient();
        GroupPageData result = client.fetchGroupPage(slGroupUuid).block();

        assertThat(result).isNotNull();
        assertThat(result.name()).isEqualTo("Title-only Group");
        assertThat(result.aboutText()).isNull();
        assertThat(result.founderUuid()).isEqualTo(founderUuid);
    }

    @Test
    void fetchGroup_allFieldsMissing_returnsAllNullExceptInputUuid() {
        // Worst-case shape (e.g., SL returns an interstitial / banner / error page
        // with no group fields). Parser stays tolerant — every field except the
        // input UUID comes back null and callers decide whether that is fatal.
        UUID slGroupUuid = UUID.randomUUID();
        String html = "<html><head></head><body><p>Nothing useful here.</p></body></html>";
        wireMock.stubFor(get(urlPathMatching("/group/.*"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html").withBody(html)));

        client = newClient();
        GroupPageData result = client.fetchGroupPage(slGroupUuid).block();

        assertThat(result).isNotNull();
        assertThat(result.slGroupUuid()).isEqualTo(slGroupUuid);
        assertThat(result.name()).isNull();
        assertThat(result.aboutText()).isNull();
        assertThat(result.founderUuid()).isNull();
    }

    @Test
    void fetchGroup_500sRepeatedly_throwsTimeoutAfterRetries() {
        // 5xx is transient — retry exhausts and we surface as ExternalApiTimeoutException
        // so the registration endpoint can map to 422 with diagnostic per spec §7.1.
        UUID slGroupUuid = UUID.randomUUID();
        wireMock.stubFor(get(urlPathMatching("/group/.*"))
                .willReturn(aResponse().withStatus(503)));

        client = newClient();
        assertThatThrownBy(() -> client.fetchGroupPage(slGroupUuid).block())
                .isInstanceOf(ExternalApiTimeoutException.class);
    }
}
