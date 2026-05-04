package com.slparcelauctions.backend.admin.infrastructure.terminals;

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.escrow.terminal.Terminal;
import com.slparcelauctions.backend.escrow.terminal.TerminalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminTerminalRotationService {

    private final TerminalSecretService secretService;
    private final TerminalRepository terminalRepo;
    private final AdminActionService adminActionService;

    private final RestClient restClient = RestClient.builder().build();

    @Transactional
    public TerminalRotationResponse rotate(Long adminUserId) {
        TerminalSecret oldCurrent = secretService.current().orElse(null);
        TerminalSecret next = secretService.rotate();

        List<Terminal> terminals = terminalRepo.findByActiveTrue();
        List<TerminalPushResult> results = new ArrayList<>(terminals.size());
        for (Terminal t : terminals) {
            results.add(pushToTerminal(t, next, oldCurrent));
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("newVersion", next.getSecretVersion());
        details.put("retiredVersion", oldCurrent != null ? oldCurrent.getSecretVersion() : null);
        List<Map<String, Object>> resultDetails = new ArrayList<>();
        for (TerminalPushResult r : results) {
            Map<String, Object> rd = new LinkedHashMap<>();
            rd.put("terminalId", r.terminalId());
            rd.put("success", r.success());
            rd.put("errorMessage", r.errorMessage() != null ? r.errorMessage() : "");
            resultDetails.add(rd);
        }
        details.put("terminalsPushedTo", resultDetails);
        adminActionService.record(
                adminUserId,
                AdminActionType.TERMINAL_SECRET_ROTATED,
                AdminActionTargetType.TERMINAL_SECRET,
                (long) next.getSecretVersion(),
                "Terminal secret rotation",
                details);

        log.info("Secret rotated to v{}: pushed to {}/{} terminals",
                next.getSecretVersion(),
                results.stream().filter(TerminalPushResult::success).count(),
                results.size());

        return new TerminalRotationResponse(
                next.getSecretVersion(), next.getSecretValue(), results);
    }

    private TerminalPushResult pushToTerminal(Terminal t, TerminalSecret next, TerminalSecret oldCurrent) {
        try {
            Map<String, Object> body = Map.of(
                    "action", "SECRET_ROTATED",
                    "newSecret", next.getSecretValue(),
                    "newVersion", next.getSecretVersion());
            restClient.post()
                    .uri(t.getHttpInUrl())
                    .header("X-SLPA-Secret", oldCurrent != null ? oldCurrent.getSecretValue() : "")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return new TerminalPushResult(t.getTerminalId(), t.getTerminalId(), true, null);
        } catch (RestClientException e) {
            log.warn("Failed to push secret to terminal {}: {}", t.getTerminalId(), e.getMessage());
            return new TerminalPushResult(t.getTerminalId(), t.getTerminalId(), false, e.getMessage());
        }
    }
}
