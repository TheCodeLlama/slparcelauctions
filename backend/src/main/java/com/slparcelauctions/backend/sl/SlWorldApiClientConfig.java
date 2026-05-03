package com.slparcelauctions.backend.sl;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;

@Configuration
public class SlWorldApiClientConfig {

    /**
     * Exposes a default {@link WebClient.Builder} so that services like
     * {@link com.slparcelauctions.backend.auction.ParcelSnapshotPhotoService}
     * can inject a builder for one-off HTTP calls without a pre-configured
     * base URL.
     */
    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    public WebClient slWorldApiWebClient(
            @Value("${slpa.world-api.base-url}") String baseUrl,
            @Value("${slpa.world-api.timeout-ms}") int timeoutMs) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMs)
                .responseTimeout(Duration.ofMillis(timeoutMs));
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.USER_AGENT, "SLPA-Backend/1.0")
                .defaultHeader(HttpHeaders.ACCEPT, "text/html")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
