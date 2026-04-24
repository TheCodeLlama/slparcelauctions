package com.slparcelauctions.backend.config.ratelimit;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * HandlerInterceptor enforcing a 60-rpm-per-IP token bucket on
 * {@code /api/v1/auctions/search}. Bucket state is persisted in Redis via
 * {@link io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager}, so
 * the cap holds across all backend replicas.
 *
 * <p>On rejection the response carries a {@code Retry-After} header and a
 * {@code application/problem+json} envelope with {@code code=TOO_MANY_REQUESTS}
 * to match the project's standard error shape.
 *
 * <p>Client IP resolution honours the first hop in {@code X-Forwarded-For}
 * when present, falling back to {@link HttpServletRequest#getRemoteAddr()}.
 * This is only safe behind a trusted reverse proxy that overwrites the
 * header — see {@code application.yml}'s {@code forward-headers-strategy}
 * comment for the deployment contract.
 */
@Component
@Slf4j
public class SearchRateLimitInterceptor implements HandlerInterceptor {

    private static final String BUCKET_KEY_PREFIX = "slpa:bucket:/auctions/search:";

    private final ProxyManager<String> proxyManager;
    private final BucketConfiguration searchBucketConfiguration;

    // ObjectMapper is thread-safe after configuration. Inline construction sidesteps the same
    // Spring Boot 4 auto-config gap that JwtAuthenticationEntryPoint works around (see FOOTGUNS
    // §B.2): the auto-configured ObjectMapper bean is not reliably available to components
    // wired this early in the context. ProblemDetail only needs basic serializers.
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SearchRateLimitInterceptor(ProxyManager<String> proxyManager,
                                      BucketConfiguration searchBucketConfiguration) {
        this.proxyManager = proxyManager;
        this.searchBucketConfiguration = searchBucketConfiguration;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp,
                             Object handler) throws Exception {
        String clientIp = resolveClientIp(req);
        String key = BUCKET_KEY_PREFIX + clientIp;

        BucketProxy bucket = proxyManager.builder().build(key, () -> searchBucketConfiguration);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            resp.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            return true;
        }

        long waitSeconds = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
        resp.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        resp.setHeader("Retry-After", String.valueOf(waitSeconds));
        resp.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too many requests - retry after " + waitSeconds + "s");
        pd.setTitle("Too Many Requests");
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", "TOO_MANY_REQUESTS");
        pd.setProperty("retryAfterSeconds", waitSeconds);

        resp.getWriter().write(objectMapper.writeValueAsString(pd));
        return false;
    }

    private String resolveClientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
