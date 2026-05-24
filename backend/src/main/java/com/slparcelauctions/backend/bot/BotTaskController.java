package com.slparcelauctions.backend.bot;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auction.parcelscan.ParcelScanService;
import com.slparcelauctions.backend.auction.parcelscan.dto.BotScanResultRequest;
import com.slparcelauctions.backend.bot.dto.BotScanFailedRequest;
import com.slparcelauctions.backend.bot.dto.BotTaskClaimRequest;
import com.slparcelauctions.backend.bot.dto.BotTaskResponse;
import com.slparcelauctions.backend.bot.dto.BotTaskResultRequest;
import com.slparcelauctions.backend.bot.dto.BuyOwnerResultRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Bot worker queue surface. Auth: bearer token (see
 * {@link BotSharedSecretAuthorizer}). After the ownership-only
 * verification refactor (spec 2026-05-16) the verify + monitor task
 * types are retired; the {@code /claim} and {@code /pending} endpoints
 * stay as future-extension scaffolding and return 204 / [] until a
 * future task type starts producing rows.
 */
@RestController
@RequestMapping("/api/v1/bot/tasks")
@RequiredArgsConstructor
public class BotTaskController {

    private final BotTaskService service;
    private final BotTaskResultService botTaskResultService;
    private final ParcelScanService parcelScanService;

    @PostMapping("/claim")
    public ResponseEntity<BotTaskResponse> claim(@Valid @RequestBody BotTaskClaimRequest body) {
        return service.claim(body.botUuid())
                .map(task -> ResponseEntity.ok(BotTaskResponse.from(task)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Read-only debug view of the PENDING queue. Retained for admin
     * triage; production workers use {@code POST /claim} which is
     * atomic and race-safe.
     */
    @GetMapping("/pending")
    public List<BotTaskResponse> pending() {
        return service.findPending().stream()
                .map(BotTaskResponse::from)
                .toList();
    }

    /**
     * Bot {@code VERIFY_SELL_TO} result callback (spec §5.1). Idempotent on
     * terminal task state -- a re-POST after a network blip is a no-op.
     */
    @PostMapping("/{taskId}/result")
    public ResponseEntity<Void> result(@PathVariable Long taskId,
            @Valid @RequestBody BotTaskResultRequest body) {
        botTaskResultService.apply(taskId, body);
        return ResponseEntity.noContent().build();
    }

    /**
     * Bot {@code VERIFY_BUY_OWNER} result callback (bot-dispatch refactor
     * 2026-05-18). A separate endpoint from the {@code VERIFY_SELL_TO} callback
     * because the outcome enum + payload shape differ -- sharing one endpoint
     * via a discriminated union would force every bot client to thread the
     * task-type through, whereas split endpoints let the bot pick the right
     * payload at the call site. Idempotent on terminal task state.
     */
    @PostMapping("/{taskId}/verify-buy-owner-result")
    public ResponseEntity<Void> verifyBuyOwnerResult(@PathVariable Long taskId,
            @Valid @RequestBody BuyOwnerResultRequest body) {
        botTaskResultService.applyVerifyBuyOwnerResult(taskId, body);
        return ResponseEntity.noContent().build();
    }

    /**
     * Bot {@code SCAN_PARCEL} result callback (parcel scanner 2026-05-23).
     * Persists the layout and height-map rasters for the auction and marks
     * the task COMPLETED. Returns 409 if the task is already completed or
     * is not of type SCAN_PARCEL. Returns 400 on malformed raster data.
     */
    @PostMapping("/{taskId}/scan-result")
    public ResponseEntity<Void> scanResult(@PathVariable Long taskId,
            @Valid @RequestBody BotScanResultRequest body) {
        parcelScanService.applyScanResult(taskId, body);
        return ResponseEntity.ok().build();
    }

    /**
     * Bot {@code SCAN_PARCEL} failure callback. Marks the task FAILED with the
     * supplied reason so the admin panel can surface why the scan did not
     * complete (e.g. {@code TERRAIN_NOT_LOADED}). Returns 409 if the task is
     * already in a terminal state or is not of type SCAN_PARCEL.
     */
    @PostMapping("/{taskId}/scan-failed")
    public ResponseEntity<Void> scanFailed(@PathVariable Long taskId,
            @Valid @RequestBody BotScanFailedRequest body) {
        parcelScanService.markScanFailed(taskId, body.reason());
        return ResponseEntity.noContent().build();
    }
}
