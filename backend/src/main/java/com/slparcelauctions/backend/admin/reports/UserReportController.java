package com.slparcelauctions.backend.admin.reports;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.admin.reports.dto.MyReportResponse;
import com.slparcelauctions.backend.admin.reports.dto.ReportRequest;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auth.AuthPrincipal;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/auctions/{auctionPublicId}")
@RequiredArgsConstructor
public class UserReportController {

    private final UserReportService service;
    private final AuctionRepository auctionRepository;

    @PostMapping("/report")
    public MyReportResponse report(
            @PathVariable UUID auctionPublicId,
            @Valid @RequestBody ReportRequest body,
            @AuthenticationPrincipal AuthPrincipal principal) {
        Long auctionId = resolveAuctionId(auctionPublicId);
        return service.upsertReport(auctionId, principal.userId(), body);
    }

    @GetMapping("/my-report")
    public ResponseEntity<MyReportResponse> myReport(
            @PathVariable UUID auctionPublicId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        Long auctionId = resolveAuctionId(auctionPublicId);
        return service.findMyReport(auctionId, principal.userId())
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.noContent().build());
    }

    private Long resolveAuctionId(UUID auctionPublicId) {
        return auctionRepository.findByPublicId(auctionPublicId)
                .map(Auction::getId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionPublicId));
    }
}
