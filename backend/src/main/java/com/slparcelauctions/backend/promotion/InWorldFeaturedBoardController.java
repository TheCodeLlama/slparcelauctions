package com.slparcelauctions.backend.promotion;

import java.time.Duration;
import java.util.List;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.promotion.dto.FeaturedBoardListingDto;
import com.slparcelauctions.backend.promotion.dto.FeaturedBoardPayloadDto;
import com.slparcelauctions.backend.promotion.exception.InvalidBoardIndexException;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/in-world")
@RequiredArgsConstructor
public class InWorldFeaturedBoardController {

    private static final Duration CACHE_TTL = Duration.ofSeconds(15);

    private final BoardContentResolver resolver;
    private final PromotionConfigProperties promotionConfig;
    private final RedisTemplate<String, Object> epic07RedisTemplate;

    @GetMapping("/featured-board/{boardIndex}")
    public FeaturedBoardPayloadDto getBoard(@PathVariable int boardIndex) {
        validateIndex(boardIndex);
        String key = "featured-board:" + boardIndex;
        Object cached = epic07RedisTemplate.opsForValue().get(key);
        if (cached instanceof FeaturedBoardPayloadDto p) return p;
        FeaturedBoardPayloadDto payload = resolver.resolve(boardIndex);
        epic07RedisTemplate.opsForValue().set(key, payload, CACHE_TTL);
        return payload;
    }

    @GetMapping("/featured-board/{boardIndex}/touch")
    public FeaturedBoardListingDto getTouchTarget(@PathVariable int boardIndex) {
        validateIndex(boardIndex);
        return resolver.currentTouchTarget(boardIndex);
    }

    @GetMapping("/board/placeholder")
    public FeaturedBoardPayloadDto getPlaceholder() {
        return new FeaturedBoardPayloadDto(0, 0, List.of(),
                FeaturedBoardPayloadDto.Source.PLACEHOLDER);
    }

    private void validateIndex(int boardIndex) {
        if (boardIndex < 1 || boardIndex > promotionConfig.featuredSlotCount()) {
            throw new InvalidBoardIndexException(boardIndex, promotionConfig.featuredSlotCount());
        }
    }
}
