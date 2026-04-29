package com.slparcelauctions.backend.admin.infrastructure.terminals;

import com.slparcelauctions.backend.escrow.terminal.Terminal;
import com.slparcelauctions.backend.escrow.terminal.TerminalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class TerminalHeartbeatService {

    private final TerminalRepository terminalRepo;
    private final Clock clock;

    @Transactional
    public void handle(TerminalHeartbeatRequest req) {
        Terminal t = terminalRepo.findById(req.terminalKey())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown terminal: " + req.terminalKey()));
        t.setLastHeartbeatAt(OffsetDateTime.now(clock));
        t.setLastReportedBalance(req.accountBalance());
        terminalRepo.save(t);
        log.info("Terminal heartbeat: {}, balance=L${}",
                t.getTerminalId(), req.accountBalance());
    }
}
