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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.reactive.function.client.WebClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
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
     * Builds a minimal World API HTML response, allowing the maturityrating
     * meta value to be customized per test. The SL XML emits the legacy
     * casing ({@code "PG"}, {@code "Mature"}, {@code "Adult"}) — see
     * {@link com.slparcelauctions.backend.parcel.MaturityRatingNormalizer}.
     */
    private static String fixtureHtml(UUID parcelUuid, UUID ownerUuid, String maturityRating) {
        return """
                <html><head>
                <meta property="og:title" content="Sunset Bay">
                <meta property="og:description" content="Waterfront parcel">
                <meta property="og:image" content="http://example.com/snap.jpg">
                <meta name="secondlife:region" content="Coniston">
                <meta name="secondlife:parcelid" content="%s">
                <meta name="ownerid" content="%s">
                <meta name="ownertype" content="agent">
                <meta name="area" content="1024">
                <meta name="maturityrating" content="%s">
                </head><body></body></html>
                """.formatted(parcelUuid, ownerUuid, maturityRating);
    }

    @Test
    void fetchParcel_validHtml_parsesMetadata() {
        UUID parcelUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID ownerUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
        String html = fixtureHtml(parcelUuid, ownerUuid, "Mature");
        wireMock.stubFor(get(urlPathMatching("/place/.*"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html").withBody(html)));

        client = newClient();
        ParcelMetadata result = client.fetchParcel(parcelUuid).block();

        assertThat(result).isNotNull();
        assertThat(result.parcelName()).isEqualTo("Sunset Bay");
        assertThat(result.regionName()).isEqualTo("Coniston");
        assertThat(result.ownerUuid()).isEqualTo(ownerUuid);
        assertThat(result.ownerType()).isEqualTo("agent");
        assertThat(result.areaSqm()).isEqualTo(1024);
        assertThat(result.maturityRating()).isEqualTo("MODERATE");
        assertThat(result.snapshotUrl()).isEqualTo("http://example.com/snap.jpg");
    }

    @ParameterizedTest
    @CsvSource({
            "PG, GENERAL",
            "Mature, MODERATE",
            "Adult, ADULT"
    })
    void fetchParcel_mapsXmlMaturityToCanonical(String xmlValue, String stored) {
        UUID parcelUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        String html = fixtureHtml(parcelUuid, ownerUuid, xmlValue);
        wireMock.stubFor(get(urlPathMatching("/place/.*"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html").withBody(html)));

        client = newClient();
        ParcelMetadata result = client.fetchParcel(parcelUuid).block();

        assertThat(result.maturityRating()).isEqualTo(stored);
    }

    @Test
    void fetchParcel_unknownMaturity_throwsParcelIngestException() {
        UUID parcelUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        String html = fixtureHtml(parcelUuid, ownerUuid, "Teen");
        wireMock.stubFor(get(urlPathMatching("/place/.*"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html").withBody(html)));

        client = newClient();
        assertThatThrownBy(() -> client.fetchParcel(parcelUuid).block())
                .isInstanceOf(ParcelIngestException.class)
                .hasMessageContaining("Teen");
    }

    @Test
    void fetchParcel_404_throwsParcelNotFound() {
        UUID parcelUuid = UUID.randomUUID();
        wireMock.stubFor(get(urlPathMatching("/place/.*"))
                .willReturn(aResponse().withStatus(404)));

        client = newClient();
        assertThatThrownBy(() -> client.fetchParcel(parcelUuid).block())
                .isInstanceOf(ParcelNotFoundInSlException.class);
    }

    @Test
    void fetchParcel_500sRepeatedly_throwsTimeoutAfterRetries() {
        UUID parcelUuid = UUID.randomUUID();
        wireMock.stubFor(get(urlPathMatching("/place/.*"))
                .willReturn(aResponse().withStatus(503)));

        client = newClient();
        assertThatThrownBy(() -> client.fetchParcel(parcelUuid).block())
                .isInstanceOf(ExternalApiTimeoutException.class);
    }
}
