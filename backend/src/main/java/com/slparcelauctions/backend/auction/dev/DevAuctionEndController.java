package com.slparcelauctions.backend.auction.dev;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.auctionend.AuctionEndTask;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Dev-profile-only triggers for the auction-end sweep. Exposes two endpoints:
 * <ul>
 *   <li>{@code POST /api/v1/dev/auction-end/run-once} — sweeps every ACTIVE
 *       auction whose {@code endsAt <= now} exactly like the scheduler cron
 *       tick, returning the list of ids that closed successfully.</li>
 *   <li>{@code POST /api/v1/dev/auctions/{id}/close} — force-closes a single
 *       auction through the same {@link AuctionEndTask#closeOne} worker,
 *       returning the id.</li>
 * </ul>
 *
 * <p>Two-layer gating (mirrors {@link com.slparcelauctions.backend.bot.DevBotTaskController}
 * and {@code DevOwnershipMonitorController}):
 * <ol>
 *   <li>{@link Profile @Profile("dev")} — the bean is not instantiated in
 *       non-dev profiles, so the route does not exist in prod.</li>
 *   <li>{@code SecurityConfig} permits {@code /api/v1/dev/**} unconditionally;
 *       the profile gate is the real trust boundary.</li>
 * </ol>
 *
 * <p>The run-once endpoint replicates the scheduler's tolerance to single-id
 * failures — a {@link RuntimeException} on one close is logged and the sweep
 * continues with the next id. The single-close endpoint lets the exception
 * propagate so integration tests can surface assertion-relevant details.
 */
@RestController
@RequestMapping("/api/v1/dev")
@Profile("dev")
@RequiredArgsConstructor
@Slf4j
public class DevAuctionEndController {

    private final AuctionRepository auctionRepo;
    private final AuctionEndTask auctionEndTask;
    private final Clock clock;

    @PostMapping("/auction-end/run-once")
    public Map<String, Object> runOnce() {
        List<Long> dueIds = auctionRepo.findActiveIdsDueForEnd(OffsetDateTime.now(clock));
        log.info("Dev-triggered auction-end sweep: {} due auctions", dueIds.size());
        List<Long> closed = new ArrayList<>();
        for (Long id : dueIds) {
            try {
                auctionEndTask.closeOne(id);
                closed.add(id);
            } catch (RuntimeException e) {
                // Mirror scheduler semantics — one failure must not abort the
                // sweep. The caller sees only the successfully-closed ids.
                log.error("closeOne({}) failed: {}", id, e.getMessage(), e);
            }
        }
        return Map.of("processed", closed);
    }

    @PostMapping("/auctions/{id}/close")
    public Map<String, Object> closeOne(@PathVariable Long id) {
        log.info("Dev-triggered single-auction close: {}", id);
        auctionEndTask.closeOne(id);
        return Map.of("closedId", id);
    }
}
