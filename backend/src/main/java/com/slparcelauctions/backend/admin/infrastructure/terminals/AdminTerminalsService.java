package com.slparcelauctions.backend.admin.infrastructure.terminals;

import com.slparcelauctions.backend.escrow.terminal.TerminalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
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
}
