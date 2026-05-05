package com.slparcelauctions.backend.admin;

import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * On startup, promotes any user whose username matches the configured
 * bootstrap-usernames list AND whose current role is USER. The
 * promote-only-currently-USER guard is a forward push at startup, not a
 * configurable opt-out — a deliberately-demoted bootstrap username will be
 * re-promoted on the next restart unless removed from the config list.
 * Matching is case-insensitive (the JPQL lowercases both sides).
 * See spec §10.6 for the full lifecycle.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrapInitializer {

    private final UserRepository userRepository;
    private final AdminBootstrapProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void promoteBootstrapAdmins() {
        if (properties.getBootstrapUsernames().isEmpty()) {
            log.info("Admin bootstrap: no bootstrap-usernames configured, skipping.");
            return;
        }
        List<String> lowercased = properties.getBootstrapUsernames().stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .toList();
        int promoted = userRepository.bulkPromoteByUsernameIfUser(lowercased);
        log.info("Admin bootstrap: promoted {} of {} configured usernames to ADMIN.",
                promoted, properties.getBootstrapUsernames().size());
    }
}
