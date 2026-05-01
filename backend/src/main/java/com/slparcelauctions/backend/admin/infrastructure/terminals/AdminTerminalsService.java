package com.slparcelauctions.backend.admin.infrastructure.terminals;

import com.slparcelauctions.backend.escrow.terminal.Terminal;
import com.slparcelauctions.backend.escrow.terminal.TerminalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminTerminalsService {

    private final TerminalRepository terminalRepo;
    private final TerminalSecretService secretService;

    @Transactional(readOnly = true)
    public List<AdminTerminalRow> list() {
        Integer currentVersion = secretService.current()
                .map(TerminalSecret::getVersion).orElse(null);
        return terminalRepo.findAll().stream().map(t -> new AdminTerminalRow(
                t.getTerminalId(), t.getRegionName(), t.getHttpInUrl(),
                t.getLastSeenAt(), t.getLastHeartbeatAt(),
                t.getLastReportedBalance(), currentVersion)).toList();
    }

    /**
     * Soft-unregister a terminal: flips {@code active = false} so the
     * dispatcher's {@code findAnyLive} query no longer returns it. The row
     * stays for forensics + audit; resetting the in-world script
     * re-registers it via {@code POST /sl/terminal/register} (which sets
     * {@code active = true} again).
     */
    @Transactional
    public void deactivate(String terminalId) {
        Terminal t = terminalRepo.findById(terminalId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "terminal not found: " + terminalId));
        t.setActive(false);
        terminalRepo.save(t);
        log.warn("Admin deactivated terminal: terminalId={}", terminalId);
    }
}
