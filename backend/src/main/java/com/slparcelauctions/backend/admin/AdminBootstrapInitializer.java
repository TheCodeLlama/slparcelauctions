package com.slparcelauctions.backend.admin;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * On startup, promotes any user whose email matches the configured
 * bootstrap-emails list AND whose current role is USER. The
 * promote-only-currently-USER guard is a forward push at startup, not a
 * configurable opt-out — a deliberately-demoted bootstrap email will be
 * re-promoted on the next restart unless removed from the config list.
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
        if (properties.getBootstrapEmails().isEmpty()) {
            log.info("Admin bootstrap: no bootstrap-emails configured, skipping.");
            return;
        }
        int promoted = userRepository.bulkPromoteByEmailIfUser(properties.getBootstrapEmails());
        log.info("Admin bootstrap: promoted {} of {} configured emails to ADMIN.",
                promoted, properties.getBootstrapEmails().size());
    }
}
