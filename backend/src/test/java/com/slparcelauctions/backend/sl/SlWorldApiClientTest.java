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

    @Test
    void fetchParcel_validHtml_parsesMetadata() {
        UUID parcelUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID ownerUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
        String html = """
                <html><head>
                <meta property="og:title" content="Sunset Bay">
                <meta property="og:description" content="Waterfront parcel">
                <meta property="og:image" content="http://example.com/snap.jpg">
                <meta name="secondlife:region" content="Coniston">
                <meta name="secondlife:parcelid" content="%s">
                <meta name="ownerid" content="%s">
                <meta name="ownertype" content="agent">
                <meta name="area" content="1024">
                <meta name="maturityrating" content="MATURE">
                </head><body></body></html>
                """.formatted(parcelUuid, ownerUuid);
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
        assertThat(result.maturityRating()).isEqualTo("MATURE");
        assertThat(result.snapshotUrl()).isEqualTo("http://example.com/snap.jpg");
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
