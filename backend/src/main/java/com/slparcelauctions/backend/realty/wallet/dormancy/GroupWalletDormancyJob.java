package com.slparcelauctions.backend.realty.wallet.dormancy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.realty.RealtyGroupRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Weekly sweep that flags newly-dormant realty groups and escalates (or auto-returns)
 * groups that are already in a dormancy phase.
 *
 * <p>Runs 30 minutes after the user-wallet dormancy job (4:00 UTC Monday) so the two
 * sweeps are independently tunable. Configured via:
 * <ul>
 *   <li>{@code slpa.realty-wallet.dormancy-job.cron} — default "0 30 4 * * MON"</li>
 *   <li>{@code slpa.realty-wallet.dormancy.window-days} — default 30</li>
 *   <li>{@code slpa.realty-wallet.dormancy.phase-duration-days} — default 7</li>
 * </ul>
 *
 * <p>Spec: docs/superpowers/specs/2026-05-11-realty-groups-group-wallet-design.md §10.2
 */
@Component
@Slf4j
public class GroupWalletDormancyJob {

    private final RealtyGroupRepository groupRepository;
    private final GroupWalletDormancyTask task;
    private final int windowDays;
    private final int phaseDurationDays;

    public GroupWalletDormancyJob(
            RealtyGroupRepository groupRepository,
            GroupWalletDormancyTask task,
            @Value("${slpa.realty-wallet.dormancy.window-days:30}") int windowDays,
            @Value("${slpa.realty-wallet.dormancy.phase-duration-days:7}") int phaseDurationDays) {
        this.groupRepository = groupRepository;
        this.task = task;
        this.windowDays = windowDays;
        this.phaseDurationDays = phaseDurationDays;
    }

    @Scheduled(cron = "${slpa.realty-wallet.dormancy-job.cron:0 30 4 * * MON}", zone = "UTC")
    public void sweep() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        log.info("group wallet dormancy sweep starting: windowDays={}, phaseDurationDays={}",
            windowDays, phaseDurationDays);

        groupRepository.findEligibleForDormancyFlag(windowDays)
            .forEach(g -> {
                try {
                    task.flag(g, now);
                } catch (Exception ex) {
                    log.error("group dormancy flag failed for groupId={}: {}",
                        g.getId(), ex.toString(), ex);
                }
            });

        groupRepository.findDormancyPhaseDue(phaseDurationDays)
            .forEach(g -> {
                try {
                    task.escalateOrAutoReturn(g, now);
                } catch (Exception ex) {
                    log.error("group dormancy escalate failed for groupId={}: {}",
                        g.getId(), ex.toString(), ex);
                }
            });

        log.info("group wallet dormancy sweep complete");
    }
}
