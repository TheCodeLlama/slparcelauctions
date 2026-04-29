package com.slparcelauctions.backend.admin.ownership;

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.monitoring.OwnershipCheckResult;
import com.slparcelauctions.backend.auction.monitoring.OwnershipCheckTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminOwnershipRecheckService {

    private final OwnershipCheckTask task;
    private final AdminActionService adminActionService;

    public AdminOwnershipRecheckResponse recheck(Long auctionId, Long adminUserId) {
        OwnershipCheckResult result = task.recheckSync(auctionId);
        boolean autoSuspended = result.auctionStatus() == AuctionStatus.SUSPENDED;

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("auctionId", auctionId);
        details.put("ownerMatch", result.ownerMatch());
        details.put("observedOwner", String.valueOf(result.observedOwner()));
        details.put("expectedOwner", String.valueOf(result.expectedOwner()));
        details.put("autoSuspended", autoSuspended);
        adminActionService.record(adminUserId,
                AdminActionType.OWNERSHIP_RECHECK_INVOKED,
                AdminActionTargetType.AUCTION,
                auctionId, null, details);

        log.info("Admin {} re-checked ownership for auction {}: match={}, observed={}",
                adminUserId, auctionId, result.ownerMatch(), result.observedOwner());

        return new AdminOwnershipRecheckResponse(
                result.ownerMatch(), result.observedOwner(),
                result.expectedOwner(), result.checkedAt(),
                result.auctionStatus());
    }
}
