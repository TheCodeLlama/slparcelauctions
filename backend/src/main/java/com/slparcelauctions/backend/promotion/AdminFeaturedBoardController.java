package com.slparcelauctions.backend.promotion;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.promotion.dto.AdminFeaturedBoardRowDto;
import com.slparcelauctions.backend.promotion.dto.MovePromotionSlotRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/featured-boards")
@RequiredArgsConstructor
public class AdminFeaturedBoardController {

    private final FeaturedBoardSlotService slotService;

    @GetMapping
    public List<AdminFeaturedBoardRowDto> list() {
        return slotService.allActive().stream()
                .map(s -> new AdminFeaturedBoardRowDto(
                        s.getPublicId(),
                        s.getBoardIndex(),
                        s.getPosition(),
                        s.getAuction().getPublicId(),
                        s.getAuction().getTitle(),
                        s.getAuction().getCurrentBid(),
                        s.getAuction().getEndsAt(),
                        s.getAssignedAt()))
                .toList();
    }

    @PostMapping("/{slotPublicId}/release")
    public void release(@PathVariable UUID slotPublicId) {
        slotService.forceRelease(slotPublicId);
    }

    @PatchMapping("/{slotPublicId}/move")
    public void move(@PathVariable UUID slotPublicId,
                     @Valid @RequestBody MovePromotionSlotRequest body) {
        slotService.move(slotPublicId, body.boardIndex(), body.position());
    }
}
