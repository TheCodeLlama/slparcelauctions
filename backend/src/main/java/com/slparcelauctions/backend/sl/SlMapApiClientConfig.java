package com.slparcelauctions.backend.sl;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;

@Configuration
public class SlMapApiClientConfig {

    @Bean
    public WebClient slMapApiWebClient(
            @Value("${slpa.map-api.base-url}") String baseUrl,
            @Value("${slpa.map-api.timeout-ms}") int timeoutMs) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMs)
                .responseTimeout(Duration.ofMillis(timeoutMs));
        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
