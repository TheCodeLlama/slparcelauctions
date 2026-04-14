package com.slparcelauctions.backend.sl;

import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.sl.dto.DevSimulateRequest;
import com.slparcelauctions.backend.sl.dto.SlVerifyRequest;
import com.slparcelauctions.backend.sl.dto.SlVerifyResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Dev-profile-only helper for exercising {@code /api/v1/sl/verify} from a browser
 * without running actual LSL scripts. Synthesizes SL headers using the first
 * trusted owner key from config, fills in any missing body fields via
 * {@link DevSimulateRequest#toSlVerifyRequest()}, then delegates to the real
 * service (same code path, same exception mapping).
 *
 * <p>Three-layer gating:
 * <ol>
 *   <li>{@code @Profile("dev")} - bean not instantiated outside dev profile.</li>
 *   <li>{@link com.slparcelauctions.backend.config.SecurityConfig} permits
 *       {@code /api/v1/dev/**} unconditionally - the bean's profile gate is
 *       the real trust boundary; in prod the request 404s at the MVC layer
 *       because no handler exists.</li>
 *   <li>{@link DevSimulateRequest#toSlVerifyRequest()} fills a random avatar
 *       UUID per call so repeated tests don't trip the unique constraint
 *       unless the caller opts in.</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/dev/sl")
@RequiredArgsConstructor
@Profile("dev")
@Slf4j
public class DevSlSimulateController {

    private final SlVerificationService slVerificationService;
    private final SlConfigProperties slConfig;

    @PostMapping("/simulate-verify")
    public SlVerifyResponse simulate(@Valid @RequestBody DevSimulateRequest req) {
        UUID ownerKey = slConfig.trustedOwnerKeys().iterator().next();
        SlVerifyRequest synthesized = req.toSlVerifyRequest();
        log.info("Dev simulate: forwarding to SlVerificationService with ownerKey={}", ownerKey);
        return slVerificationService.verify(
                slConfig.expectedShard(),
                ownerKey.toString(),
                synthesized);
    }
}
