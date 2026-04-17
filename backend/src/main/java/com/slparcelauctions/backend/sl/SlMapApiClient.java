package com.slparcelauctions.backend.sl;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import com.slparcelauctions.backend.sl.dto.GridCoordinates;
import com.slparcelauctions.backend.sl.exception.ExternalApiTimeoutException;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Resolves a region name to (grid_x, grid_y) via the SL Map CAP endpoint.
 * Response is JavaScript-ish: {@code coords[0] = x; coords[1] = y;}.
 */
@Component
@Slf4j
public class SlMapApiClient {

    private static final Pattern COORD_PATTERN = Pattern.compile(
            "coords\\[(\\d+)\\]\\s*=\\s*([\\d.]+)");

    private final WebClient webClient;
    private final String capUuid;

    @Autowired
    public SlMapApiClient(
            @Qualifier("slMapApiWebClient") WebClient webClient,
            @Value("${slpa.map-api.cap-uuid}") String capUuid) {
        this.webClient = webClient;
        this.capUuid = capUuid;
    }

    public Mono<GridCoordinates> resolveRegion(String regionName) {
        String body = "var=" + regionName.replace(" ", "%20");
        return webClient.post()
                .uri("/cap/0/{cap}", capUuid)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromValue(body))
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parse)
                .onErrorMap(
                        throwable -> !(throwable instanceof ExternalApiTimeoutException),
                        throwable -> new ExternalApiTimeoutException("Map", throwable.getMessage()));
    }

    private GridCoordinates parse(String response) {
        Double x = null;
        Double y = null;
        Matcher m = COORD_PATTERN.matcher(response);
        while (m.find()) {
            int idx = Integer.parseInt(m.group(1));
            double val = Double.parseDouble(m.group(2));
            if (idx == 0) x = val;
            if (idx == 1) y = val;
        }
        if (x == null || y == null) {
            throw new ExternalApiTimeoutException("Map", "Response missing coords[0]/coords[1]");
        }
        return new GridCoordinates(x, y);
    }
}
