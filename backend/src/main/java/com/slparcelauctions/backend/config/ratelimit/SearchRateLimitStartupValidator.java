package com.slparcelauctions.backend.config.ratelimit;

import java.util.Arrays;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import lombok.extern.slf4j.Slf4j;

/**
 * Fails fast on startup if the search rate limiter is not wired in any
 * non-dev profile. The interceptor + WebMvcConfig are gated on
 * {@code @ConditionalOnBean(ProxyManager.class)} so {@code @WebMvcTest}
 * slices can boot without Redis — but in production that gate is
 * dangerous: a wiring failure (bad Redis host, codec mismatch) silently
 * disables the 60rpm/IP cap on {@code /auctions/search}. This validator
 * catches that.
 */
@Component
@Slf4j
public class SearchRateLimitStartupValidator {

    private final Environment env;
    private final ProxyManager<String> proxyManager;
    private final SearchRateLimitInterceptor interceptor;

    public SearchRateLimitStartupValidator(
            Environment env,
            ObjectProvider<ProxyManager<String>> proxyManagerProvider,
            ObjectProvider<SearchRateLimitInterceptor> interceptorProvider) {
        this.env = env;
        this.proxyManager = proxyManagerProvider.getIfAvailable();
        this.interceptor = interceptorProvider.getIfAvailable();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verify() {
        boolean isDev = Arrays.asList(env.getActiveProfiles()).contains("dev")
                || Arrays.asList(env.getActiveProfiles()).contains("test");
        if (isDev) {
            return;
        }
        if (proxyManager == null || interceptor == null) {
            throw new IllegalStateException(
                    "Search rate limiter not wired (proxyManager=" + (proxyManager != null)
                    + ", interceptor=" + (interceptor != null) + "). "
                    + "/auctions/search would serve unthrottled. Refusing to start.");
        }
        log.info("Search rate limiter ARMED (60rpm/IP via bucket4j Lettuce)");
    }
}
