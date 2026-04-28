package com.slparcelauctions.backend.admin.infrastructure.reminders;

import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Daily scheduler (09:00 UTC) that fires a once-per-escrow transfer reminder
 * when the escrow's 72-hour transfer deadline is 12–36 hours away.
 *
 * <p>Window: {@code transferDeadline ∈ [now+12h, now+36h]}. The Escrow entity
 * stores {@code transferDeadline} directly, so the BETWEEN range is the
 * deadline window, not a fundedAt-based rewrite.
 *
 * <p>Each qualifying escrow has {@code reminderSentAt} stamped after the
 * notification fires, preventing duplicate reminders on subsequent daily runs.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "slpa.escrow-transfer-reminder",
        name = "enabled",
        matchIfMissing = true)
@Slf4j
public class EscrowTransferReminderScheduler {

    /** Leading-edge of the reminder window relative to now. */
    private static final long REMINDER_WINDOW_LEAD_HOURS = 12L;

    /** Trailing-edge of the reminder window relative to now. */
    private static final long REMINDER_WINDOW_TRAIL_HOURS = 36L;

    private final EscrowRepository escrowRepo;
    private final NotificationPublisher publisher;
    private final Clock clock;

    @Scheduled(cron = "${slpa.escrow-transfer-reminder.cron:0 0 9 * * *}", zone = "UTC")
    @Transactional
    public void run() {
        OffsetDateTime now = OffsetDateTime.now(clock);

        // Reminder fires when transferDeadline ∈ [now+12h, now+36h].
        OffsetDateTime rangeStart = now.plusHours(REMINDER_WINDOW_LEAD_HOURS);
        OffsetDateTime rangeEnd   = now.plusHours(REMINDER_WINDOW_TRAIL_HOURS);

        List<Escrow> rows = escrowRepo.findEscrowsApproachingTransferDeadline(rangeStart, rangeEnd);

        for (Escrow e : rows) {
            publisher.escrowTransferReminder(
                    e.getAuction().getSeller().getId(),
                    e.getAuction().getId(),
                    e.getId(),
                    e.getAuction().getTitle(),
                    e.getTransferDeadline());
            e.setReminderSentAt(now);
            escrowRepo.save(e);
        }

        log.info("EscrowTransferReminderScheduler: reminded {} escrow(s)", rows.size());
    }
}
