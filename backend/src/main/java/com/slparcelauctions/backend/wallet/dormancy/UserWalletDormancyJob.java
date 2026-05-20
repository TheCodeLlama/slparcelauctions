package com.slparcelauctions.backend.wallet.dormancy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.user.UserRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Weekly sweep that flags newly-dormant users and escalates (or auto-returns)
 * users already in a dormancy phase.
 *
 * <p>Runs 30 minutes BEFORE the group-wallet dormancy job so the user-side
 * sweep completes before any member-login side effects from the group sweep
 * fire. Configured via:
 * <ul>
 *   <li>{@code slpa.wallet.dormancy-job.cron} -- default "0 0 4 * * MON"</li>
 *   <li>{@code slpa.wallet.dormancy.window-days} -- default 30</li>
 *   <li>{@code slpa.wallet.dormancy.phase-duration-days} -- default 7</li>
 * </ul>
 *
 * <p>Spec: docs/superpowers/specs/2026-05-19-user-wallet-dormancy-design.md §8
 */
@Component
@Slf4j
public class UserWalletDormancyJob {

    private final UserRepository userRepository;
    private final UserWalletDormancyTask task;
    private final int windowDays;
    private final int phaseDurationDays;

    public UserWalletDormancyJob(
            UserRepository userRepository,
            UserWalletDormancyTask task,
            @Value("${slpa.wallet.dormancy.window-days:30}") int windowDays,
            @Value("${slpa.wallet.dormancy.phase-duration-days:7}") int phaseDurationDays) {
        this.userRepository = userRepository;
        this.task = task;
        this.windowDays = windowDays;
        this.phaseDurationDays = phaseDurationDays;
    }

    @Scheduled(cron = "${slpa.wallet.dormancy-job.cron:0 0 4 * * MON}", zone = "UTC")
    public void sweep() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        log.info("user wallet dormancy sweep starting: windowDays={}, phaseDurationDays={}",
            windowDays, phaseDurationDays);

        userRepository.findEligibleForDormancyFlag(windowDays)
            .forEach(u -> {
                try {
                    task.flag(u, now);
                } catch (Exception ex) {
                    log.error("user dormancy flag failed for userId={}: {}",
                        u.getId(), ex.toString(), ex);
                }
            });

        userRepository.findDormancyPhaseDue(phaseDurationDays)
            .forEach(u -> {
                try {
                    task.escalateOrAutoReturn(u, now);
                } catch (Exception ex) {
                    log.error("user dormancy escalate failed for userId={}: {}",
                        u.getId(), ex.toString(), ex);
                }
            });

        log.info("user wallet dormancy sweep complete");
    }
}
