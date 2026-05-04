package com.slparcelauctions.backend.admin;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.admin.AdminAuctionService.AdminAuctionReinstateResult;
import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.admin.dto.AdminAuctionReinstateResponse;
import com.slparcelauctions.backend.admin.dto.ReinstateAuctionRequest;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auth.AuthPrincipal;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/auctions")
@RequiredArgsConstructor
public class AdminAuctionController {

    private final AdminAuctionService adminAuctionService;
    private final AdminActionService adminActionService;
    private final AuctionRepository auctionRepository;

    @PostMapping("/{publicId}/reinstate")
    public AdminAuctionReinstateResponse reinstate(
            @PathVariable("publicId") UUID publicId,
            @Valid @RequestBody ReinstateAuctionRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {

        Long auctionId = auctionRepository.findByPublicId(publicId)
                .map(Auction::getId)
                .orElseThrow(() -> new AuctionNotFoundException(publicId));

        AdminAuctionReinstateResult result = adminAuctionService.reinstate(auctionId, Optional.empty());
        adminActionService.record(admin.userId(),
            AdminActionType.REINSTATE_LISTING, AdminActionTargetType.LISTING, auctionId,
            body.notes(), null);

        return new AdminAuctionReinstateResponse(
            result.auction().getId(),
            result.auction().getStatus(),
            result.newEndsAt(),
            result.suspensionDuration().toSeconds());
    }
}
