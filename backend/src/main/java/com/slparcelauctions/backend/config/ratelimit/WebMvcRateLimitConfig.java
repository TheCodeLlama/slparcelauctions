package com.slparcelauctions.backend.config.ratelimit;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import lombok.RequiredArgsConstructor;

/**
 * Registers {@link SearchRateLimitInterceptor} against the single
 * {@code /api/v1/auctions/search} path. The other Epic 07 read endpoints
 * — {@code /auctions/featured/*} and {@code /stats/public} — intentionally
 * stay outside the bucket: their bounded URL cardinality plus 60-second
 * Redis read-through caches already cap origin load.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcRateLimitConfig implements WebMvcConfigurer {

    private final SearchRateLimitInterceptor interceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor)
                .addPathPatterns("/api/v1/auctions/search");
    }
}
