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
import com.slparcelauctions.backend.region.dto.RegionPageData;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.dto.ParcelPageData;
import com.slparcelauctions.backend.sl.exception.ExternalApiTimeoutException;
import com.slparcelauctions.backend.sl.exception.ParcelNotFoundInSlException;

class SlWorldApiClientTest {

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
     * Realistic World API response shape (matches what
     * {@code world.secondlife.com/place/{uuid}} actually serves today). Test
     * fixtures take ownerType + an explicit owner UUID string so per-test
     * variants (agent vs. group, missing owner) can drive the parser without
     * conditional fixture builders.
     */
    private static String fixtureHtml(UUID parcelUuid, String ownerType, String ownerUuidContent) {
        return """
                <html><head>
                <meta name="region" content="Tula">
                <meta name="snapshot" content="">
                <meta name="mat" content="M_NOT">
                <meta name="parcel" content="Grass land 512sqm - Tula [M]">
                <meta name="parcelid" content="%s">
                <meta name="area" content="512">
                <meta name="ownerid" content="%s">
                <meta name="ownertype" content="%s">
                <meta name="owner" content="Heath Onyx">
                <meta name="location" content="80/104/0">
                </head><body>
                  <div class="details_content">
                    <p class="desc">Super awesome plot of land. It&#x27;s a rectangle!</p>
                    <a class="button teleport web_link"
                       href="https://maps.secondlife.com/secondlife/Tula/80/104/0/">Visit</a>
                  </div>
                  <div class="img">
                    <img src="https://picture-service.secondlife.com/snap-uuid/256x192.jpg"
                         alt="parcel image" class="parcelimg">
                  </div>
                </body></html>
                """.formatted(parcelUuid, ownerUuidContent, ownerType);
    }

    @Test
    void fetchParcel_validHtml_parsesAllFields() {
        UUID parcelUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID ownerUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
        String html = fixtureHtml(parcelUuid, "agent", ownerUuid.toString());
        wireMock.stubFor(get(urlPathMatching("/place/.*"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html").withBody(html)));

        client = newClient();
        ParcelMetadata result = client.fetchParcelPage(parcelUuid).block().parcel();

        assertThat(result).isNotNull();
        assertThat(result.parcelName()).isEqualTo("Grass land 512sqm - Tula [M]");
        assertThat(result.regionName()).isEqualTo("Tula");
        assertThat(result.ownerUuid()).isEqualTo(ownerUuid);
        assertThat(result.ownerType()).isEqualTo("agent");
        assertThat(result.areaSqm()).isEqualTo(512);
        assertThat(result.description()).isEqualTo("Super awesome plot of land. It's a rectangle!");
        assertThat(result.snapshotUrl())
                .isEqualTo("https://picture-service.secondlife.com/snap-uuid/256x192.jpg");
        assertThat(result.positionX()).isEqualTo(80.0);
        assertThat(result.positionY()).isEqualTo(104.0);
        assertThat(result.positionZ()).isEqualTo(0.0);
        // Maturity is region-scoped in SL; the parcel page does not reliably
        // expose it. Always null at ingest until user-supplied at listing time.
        assertThat(result.maturityRating()).isNull();
    }

    @Test
    void fetchParcel_groupOwnedParcel_ownerUuidIsNull() {
        // Group-owned parcels: SL omits a parseable ownerid (the field renders
        // empty or with a non-UUID placeholder). Parser must tolerate this so
        // the lookup succeeds; ownership is settled later at script verify time.
        UUID parcelUuid = UUID.randomUUID();
        String html = fixtureHtml(parcelUuid, "group", "");
        wireMock.stubFor(get(urlPathMatching("/place/.*"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html").withBody(html)));

        client = newClient();
        ParcelMetadata result = client.fetchParcelPage(parcelUuid).block().parcel();

        assertThat(result).isNotNull();
        assertThat(result.ownerType()).isEqualTo("group");
        assertThat(result.ownerUuid()).isNull();
        assertThat(result.regionName()).isEqualTo("Tula");
    }

    @Test
    void fetchParcel_snapshotMetaPopulated_winsOverParcelImg() {
        // When SL fills in name=snapshot we trust it over the rendered <img>.
        UUID parcelUuid = UUID.randomUUID();
        String html = """
                <html><head>
                <meta name="region" content="Coniston">
                <meta name="snapshot" content="https://example.com/explicit.jpg">
                <meta name="ownertype" content="agent">
                <meta name="ownerid" content="22222222-2222-2222-2222-222222222222">
                <meta name="parcel" content="Coniston Plot">
                <meta name="area" content="1024">
                <meta name="location" content="128/128/22">
                </head><body>
                <img src="https://wrong.example.com/img.jpg" class="parcelimg">
                </body></html>
                """;
        wireMock.stubFor(get(urlPathMatching("/place/.*"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html").withBody(html)));

        client = newClient();
        ParcelMetadata result = client.fetchParcelPage(parcelUuid).block().parcel();

        assertThat(result.snapshotUrl()).isEqualTo("https://example.com/explicit.jpg");
    }

    @Test
    void fetchParcel_missingOptionalFields_parserStaysTolerant() {
        // The parcel page sometimes omits description and location entirely
        // (e.g., a brand-new parcel with no description set). Parser must
        // return null/null/null for those rather than throwing.
        UUID parcelUuid = UUID.randomUUID();
        String html = """
                <html><head>
                <meta name="region" content="Coniston">
                <meta name="ownertype" content="agent">
                <meta name="ownerid" content="22222222-2222-2222-2222-222222222222">
                <meta name="parcel" content="Coniston Plot">
                <meta name="area" content="1024">
                </head><body></body></html>
                """;
        wireMock.stubFor(get(urlPathMatching("/place/.*"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html").withBody(html)));

        client = newClient();
        ParcelMetadata result = client.fetchParcelPage(parcelUuid).block().parcel();

        assertThat(result.description()).isNull();
        assertThat(result.snapshotUrl()).isNull();
        assertThat(result.positionX()).isNull();
        assertThat(result.positionY()).isNull();
        assertThat(result.positionZ()).isNull();
    }

    @Test
    void fetchParcel_malformedLocationMeta_positionFieldsNull() {
        // Location is "x/y/z" — only three slash-separated parts. Anything
        // shorter or longer is treated as a parser-incompatible value rather
        // than partially filled.
        UUID parcelUuid = UUID.randomUUID();
        String html = """
                <html><head>
                <meta name="region" content="Coniston">
                <meta name="ownertype" content="agent">
                <meta name="ownerid" content="22222222-2222-2222-2222-222222222222">
                <meta name="parcel" content="Coniston Plot">
                <meta name="area" content="1024">
                <meta name="location" content="80/104">
                </head><body></body></html>
                """;
        wireMock.stubFor(get(urlPathMatching("/place/.*"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html").withBody(html)));

        client = newClient();
        ParcelMetadata result = client.fetchParcelPage(parcelUuid).block().parcel();

        assertThat(result.positionX()).isNull();
        assertThat(result.positionY()).isNull();
        assertThat(result.positionZ()).isNull();
    }

    @Test
    void fetchParcel_404_throwsParcelNotFound() {
        UUID parcelUuid = UUID.randomUUID();
        wireMock.stubFor(get(urlPathMatching("/place/.*"))
                .willReturn(aResponse().withStatus(404)));

        client = newClient();
        assertThatThrownBy(() -> client.fetchParcelPage(parcelUuid).block())
                .isInstanceOf(ParcelNotFoundInSlException.class);
    }

    @Test
    void fetchParcel_500sRepeatedly_throwsTimeoutAfterRetries() {
        UUID parcelUuid = UUID.randomUUID();
        wireMock.stubFor(get(urlPathMatching("/place/.*"))
                .willReturn(aResponse().withStatus(503)));

        client = newClient();
        assertThatThrownBy(() -> client.fetchParcelPage(parcelUuid).block())
                .isInstanceOf(ExternalApiTimeoutException.class);
    }
}
