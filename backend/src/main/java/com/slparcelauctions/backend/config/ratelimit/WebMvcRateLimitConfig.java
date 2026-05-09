package com.slparcelauctions.backend.config.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import lombok.RequiredArgsConstructor;

/**
 * Registers {@link SearchRateLimitInterceptor} against the single
 * {@code /api/v1/auctions/search} path and {@link
 * SuggestRateLimitInterceptor} against {@code /api/v1/search/suggest}.
 * The other Epic 07 read endpoints — {@code /auctions/featured/*} and
 * {@code /stats/public} — intentionally stay outside the bucket: their
 * bounded URL cardinality plus 60-second Redis read-through caches
 * already cap origin load.
 *
 * <p>Gated on the search interceptor bean so {@code @WebMvcTest} slice
 * contexts — which skip {@link SearchRateLimitConfig} — do not fail to
 * load with an UnsatisfiedDependencyException for either interceptor.
 */
@Configuration
@ConditionalOnBean(SearchRateLimitInterceptor.class)
@RequiredArgsConstructor
public class WebMvcRateLimitConfig implements WebMvcConfigurer {

    private final SearchRateLimitInterceptor searchInterceptor;
    private final SuggestRateLimitInterceptor suggestInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(searchInterceptor)
                .addPathPatterns("/api/v1/auctions/search");
        registry.addInterceptor(suggestInterceptor)
                .addPathPatterns("/api/v1/search/suggest");
    }
}
