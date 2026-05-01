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
        OffsetDateTime now = OffsetDateTime.now(clock);
        t.setLastHeartbeatAt(now);
        t.setLastReportedBalance(req.accountBalance());
        // Heartbeats also keep the terminal "live" for the dispatcher.
        // Without this, the dispatcher's lastSeenAt-based live window would
        // expire even on a healthy heartbeating terminal.
        t.setLastSeenAt(now);
        terminalRepo.save(t);
        log.info("Terminal heartbeat: {}, balance=L${}",
                t.getTerminalId(), req.accountBalance());
    }
}
