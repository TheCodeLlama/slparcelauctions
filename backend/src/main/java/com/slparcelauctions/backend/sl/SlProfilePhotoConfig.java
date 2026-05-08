package com.slparcelauctions.backend.sl;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import reactor.netty.http.client.HttpClient;

/**
 * WebClient beans for the SL profile-photo scrape pipeline. Two clients —
 * one for the resident HTML page on {@code world.secondlife.com}, one for
 * the texture bytes on {@code picture-service.secondlife.com}. Both share
 * a 5s connect + 5s read timeout and a 2 MB in-memory size cap so a
 * runaway response from either host can't pin a worker thread or pull
 * unbounded heap.
 */
@Configuration
public class SlProfilePhotoConfig {

    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int CONNECT_MS = 5_000;
    private static final int READ_S = 5;

    @Bean
    public WebClient slWorldWebClient() {
        return clientBuilder()
                .baseUrl("https://world.secondlife.com")
                .build();
    }

    @Bean
    public WebClient slPictureServiceWebClient() {
        return clientBuilder()
                .baseUrl("https://picture-service.secondlife.com")
                .build();
    }

    private static WebClient.Builder clientBuilder() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_MS)
                .doOnConnected(c -> c.addHandlerLast(new ReadTimeoutHandler(READ_S)));
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_BYTES))
                .build();
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies);
    }
}
