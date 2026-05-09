package com.slparcelauctions.backend.config.ratelimit;

import java.net.URI;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
 * HandlerInterceptor enforcing a 300-rpm-per-IP token bucket on
 * {@code /api/v1/search/suggest}. Sized higher than the structured
 * /auctions/search bucket (60 rpm) because typeahead amplifies request
 * count by roughly 10x — one debounced fetch per word the user types.
 *
 * <p>Reuses the same Lettuce {@link ProxyManager} as the search
 * interceptor; only the {@link BucketConfiguration} differs. Mirrors
 * the search interceptor's response shape on rejection: 429 + {@code
 * Retry-After} header + {@code application/problem+json} body.
 */
@Component
@ConditionalOnBean(ProxyManager.class)
@Slf4j
public class SuggestRateLimitInterceptor implements HandlerInterceptor {

    private static final String BUCKET_KEY_PREFIX = "slpa:bucket:/search/suggest:";

    private final ProxyManager<String> proxyManager;
    private final BucketConfiguration suggestBucketConfiguration;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public SuggestRateLimitInterceptor(ProxyManager<String> proxyManager,
                                       BucketConfiguration suggestBucketConfiguration) {
        this.proxyManager = proxyManager;
        this.suggestBucketConfiguration = suggestBucketConfiguration;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp,
                             Object handler) throws Exception {
        String clientIp = resolveClientIp(req);
        String key = BUCKET_KEY_PREFIX + clientIp;

        BucketProxy bucket = proxyManager.builder().build(key, () -> suggestBucketConfiguration);
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
