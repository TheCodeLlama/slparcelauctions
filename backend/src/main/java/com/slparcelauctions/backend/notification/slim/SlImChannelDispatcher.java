package com.slparcelauctions.backend.notification.slim;

import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationEvent;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two entry points for queueing an SL IM:
 * <ul>
 *   <li>{@link #maybeQueue(NotificationEvent)} — single-recipient. Caller has
 *       registered this as an afterCommit hook on the parent transaction; we
 *       open our own REQUIRES_NEW so failures don't propagate.</li>
 *   <li>{@link #maybeQueueForFanout(long, NotificationCategory, String, String, Map, String)}
 *       — fan-out. Caller is already inside a REQUIRES_NEW per-recipient lambda;
 *       this writes as a sibling, atomic with the in-app row for that recipient.
 *       Failures here propagate (caller's per-recipient try-catch isolates).</li>
 * </ul>
 *
 * <p>Both paths read User fresh inside their respective transactions so a
 * preferences PUT before the dispatch has read-your-writes semantics.
 */
@Component
@Slf4j
public class SlImChannelDispatcher {

    private final UserRepository userRepo;
    private final SlImChannelGate gate;
    private final SlImMessageBuilder messageBuilder;
    private final SlImLinkResolver linkResolver;
    private final SlImMessageDao dao;
    private final TransactionTemplate requiresNewTx;

    // Explicit constructor — @RequiredArgsConstructor doesn't propagate @Qualifier
    // (see sub-spec 1 Task 3 deviation note).
    public SlImChannelDispatcher(
        UserRepository userRepo,
        SlImChannelGate gate,
        SlImMessageBuilder messageBuilder,
        SlImLinkResolver linkResolver,
        SlImMessageDao dao,
        @Qualifier("requiresNewTxTemplate") TransactionTemplate requiresNewTx
    ) {
        this.userRepo = userRepo;
        this.gate = gate;
        this.messageBuilder = messageBuilder;
        this.linkResolver = linkResolver;
        this.dao = dao;
        this.requiresNewTx = requiresNewTx;
    }

    public void maybeQueue(NotificationEvent event) {
        try {
            requiresNewTx.execute(status -> {
                doQueue(event.userId(), event.category(),
                    event.title(), event.body(), event.data(), event.coalesceKey());
                return null;
            });
        } catch (Exception ex) {
            log.warn("SL IM dispatch failed for userId={} category={}: {}",
                event.userId(), event.category(), ex.toString());
        }
    }

    /**
     * Fan-out variant. Caller (inside a per-recipient REQUIRES_NEW) gets atomic
     * commit semantics: in-app row + IM queue row commit together or neither
     * does. Caller's try-catch isolates this recipient's failure from siblings.
     */
    public void maybeQueueForFanout(
        long userId, NotificationCategory category,
        String title, String body, Map<String, Object> data, String coalesceKey
    ) {
        doQueue(userId, category, title, body, data, coalesceKey);
    }

    private void doQueue(
        long userId, NotificationCategory category,
        String title, String body, Map<String, Object> data, String coalesceKey
    ) {
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new IllegalStateException("user not found: " + userId));

        SlImChannelGate.Decision decision = gate.decide(user, category);
        log.debug("SL IM gate decision userId={} category={}: {}", userId, category, decision);

        switch (decision) {
            case QUEUE, QUEUE_BYPASS_PREFS -> {
                String deeplink = linkResolver.resolve(category, data);
                String messageText = messageBuilder.assemble(title, body, deeplink);
                dao.upsert(userId, user.getSlAvatarUuid().toString(), messageText, coalesceKey);
            }
            case SKIP_NO_AVATAR, SKIP_MUTED, SKIP_GROUP_DISABLED -> {
                // intentional no-op; decision logged at DEBUG above
            }
        }
    }
}
