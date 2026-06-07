package com.slparcelauctions.backend.promotion;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.promotion.dto.FeaturedBoardListingDto;
import com.slparcelauctions.backend.promotion.dto.FeaturedBoardPayloadDto;
import com.slparcelauctions.backend.promotion.exception.InvalidBoardIndexException;

import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/v1/in-world")
@Slf4j
public class InWorldFeaturedBoardController {

    private static final Duration CACHE_TTL = Duration.ofSeconds(15);
    private static final String CACHE_KEY_PREFIX = "slpa:featured-board:";

    private final BoardContentResolver resolver;
    private final PromotionConfigProperties promotionConfig;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Lombok cannot propagate @Qualifier through @RequiredArgsConstructor, so the
     * constructor is hand-written to bind the correct Redis bean explicitly.
     */
    public InWorldFeaturedBoardController(
            BoardContentResolver resolver,
            PromotionConfigProperties promotionConfig,
            @Qualifier("epic07RedisTemplate") RedisTemplate<String, Object> redisTemplate) {
        this.resolver = resolver;
        this.promotionConfig = promotionConfig;
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/featured-board/{boardIndex}")
    public FeaturedBoardPayloadDto getBoard(@PathVariable int boardIndex) {
        validateIndex(boardIndex);
        String key = CACHE_KEY_PREFIX + boardIndex;

        FeaturedBoardPayloadDto cached = null;
        try {
            Object raw = redisTemplate.opsForValue().get(key);
            if (raw instanceof FeaturedBoardPayloadDto p) cached = p;
        } catch (RuntimeException e) {
            log.warn("Redis read failed for {} (degrading to recompute): {}", key, e.getMessage());
        }
        if (cached != null) return cached;

        FeaturedBoardPayloadDto payload = resolver.resolve(boardIndex);
        try {
            redisTemplate.opsForValue().set(key, payload, CACHE_TTL);
        } catch (RuntimeException e) {
            log.warn("Redis write failed for {} (continuing without cache): {}", key, e.getMessage());
        }
        return payload;
    }

    @GetMapping("/featured-board/{boardIndex}/touch")
    public FeaturedBoardListingDto getTouchTarget(@PathVariable int boardIndex) {
        validateIndex(boardIndex);
        return resolver.currentTouchTarget(boardIndex);
    }

    @GetMapping("/board/placeholder")
    public ResponseEntity<FeaturedBoardPayloadDto> getPlaceholder() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                .body(new FeaturedBoardPayloadDto(0, 0, List.of(),
                        FeaturedBoardPayloadDto.Source.PLACEHOLDER));
    }

    private void validateIndex(int boardIndex) {
        if (boardIndex < 1 || boardIndex > promotionConfig.featuredSlotCount()) {
            throw new InvalidBoardIndexException(boardIndex, promotionConfig.featuredSlotCount());
        }
    }
}
