package com.slparcelauctions.backend.sl;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.slparcelauctions.backend.sl.dto.GridCoordinates;
import com.slparcelauctions.backend.sl.exception.RegionNotFoundException;

class SlMapApiClientTest {

    private static WireMockServer wireMock;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @AfterEach
    void reset() {
        wireMock.resetAll();
    }

    private SlMapApiClient newClient() {
        WebClient webClient = WebClient.builder().baseUrl("http://localhost:" + wireMock.port()).build();
        return new SlMapApiClient(webClient, "b713fe80-283b-4585-af4d-a3b7d9a32492", 3, 10L);
    }

    @Test
    void resolveRegion_validResponse_parsesCoordinates() {
        // Current SL CAP shape: var <name> = {'x' : N, 'y' : N };
        // The legacy `coords[0] = X` shape is no longer served.
        wireMock.stubFor(post(urlPathMatching("/cap/0/.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/javascript")
                        .withBody("var loc = {'x' : 260000, 'y' : 254000 };")));

        GridCoordinates result = newClient().resolveRegion("Coniston").block();
        assertThat(result).isNotNull();
        assertThat(result.gridX()).isEqualTo(260000.0);
        assertThat(result.gridY()).isEqualTo(254000.0);
    }

    @Test
    void resolveRegion_emptyResponse_throwsRegionNotFound() {
        // SL responds without an 'x'/'y' object literal when the region
        // doesn't exist. Whatever shape it takes, the parser only knows how
        // to find coordinates — anything else collapses to "not found".
        wireMock.stubFor(post(urlPathMatching("/cap/0/.*"))
                .willReturn(aResponse().withStatus(200).withBody("var loc = {};")));

        assertThatThrownBy(() -> newClient().resolveRegion("NoSuchRegion").block())
                .isInstanceOf(RegionNotFoundException.class);
    }
}
