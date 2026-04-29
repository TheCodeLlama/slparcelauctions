package com.slparcelauctions.backend.admin.infrastructure.terminals;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TerminalSecretService {

    private static final SecureRandom RNG = new SecureRandom();

    private final TerminalSecretRepository repo;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Optional<TerminalSecret> current() {
        return repo.findByRetiredAtIsNullOrderByVersionDesc().stream().findFirst();
    }

    @Transactional(readOnly = true)
    public boolean accept(String rawSecret) {
        return repo.findByRetiredAtIsNullOrderByVersionDesc().stream()
                .anyMatch(s -> s.getSecretValue().equals(rawSecret));
    }

    @Transactional
    public TerminalSecret rotate() {
        int nextVersion = repo.findTopByOrderByVersionDesc()
                .map(s -> s.getVersion() + 1)
                .orElse(1);
        List<TerminalSecret> active = repo.findByRetiredAtIsNullOrderByVersionDesc();
        if (active.size() >= 2) {
            TerminalSecret oldest = active.get(active.size() - 1);
            oldest.setRetiredAt(OffsetDateTime.now(clock));
            repo.save(oldest);
        }
        byte[] bytes = new byte[32];
        RNG.nextBytes(bytes);
        String newValue = HexFormat.of().formatHex(bytes);
        TerminalSecret next = TerminalSecret.builder()
                .version(nextVersion)
                .secretValue(newValue)
                .build();
        TerminalSecret saved = repo.save(next);
        log.info("Terminal secret rotated to v{}", nextVersion);
        return saved;
    }
}
