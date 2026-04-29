package com.slparcelauctions.backend.admin.ownership;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/auctions")
@RequiredArgsConstructor
public class AdminOwnershipRecheckController {

    private final AdminOwnershipRecheckService service;

    @PostMapping("/{id}/recheck-ownership")
    public AdminOwnershipRecheckResponse recheck(
            @PathVariable("id") Long auctionId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return service.recheck(auctionId, principal.userId());
    }
}
