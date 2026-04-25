package com.slparcelauctions.backend.notification;

import com.slparcelauctions.backend.notification.NotificationDao.UpsertResult;
import com.slparcelauctions.backend.notification.dto.NotificationDto;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class NotificationService {

    private final NotificationDao dao;
    private final NotificationRepository repo;
    private final NotificationWsBroadcasterPort wsBroadcaster;

    /**
     * Publishes a notification within the caller's transaction.
     *
     * <p>Uses {@code Propagation.MANDATORY} — callers must open a transaction
     * before invoking this method. The WebSocket broadcast is deferred to
     * {@code afterCommit} so it is never fired for rolled-back transactions.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UpsertResult publish(NotificationEvent event) {
        UpsertResult result = dao.upsert(
                event.userId(), event.category(), event.title(), event.body(),
                event.data(), event.coalesceKey());

        long userId = event.userId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                wsBroadcaster.broadcastUpsert(userId, result, dtoFromUpsert(event, result));
            }
        });
        return result;
    }

    /**
     * Marks a single notification as read. Idempotent — calling this on an
     * already-read row is silently accepted (no broadcast).
     *
     * @throws NoSuchElementException if the notification does not belong to {@code userId}
     */
    public void markRead(long userId, long notificationId) {
        int affected = repo.markRead(notificationId, userId);
        if (affected == 0) {
            if (!repo.existsByIdAndUserId(notificationId, userId)) {
                throw new NoSuchElementException("notification not found");
            }
            return; // already-read; idempotent, no broadcast
        }
        registerReadStateBroadcast(userId);
    }

    /**
     * Marks all unread notifications for the given user as read, optionally
     * scoped to a single {@link NotificationGroup}.
     *
     * @param group the group to scope the operation to, or {@code null} for all
     * @return the number of rows updated
     */
    public int markAllRead(long userId, NotificationGroup group) {
        int affected = (group != null)
                ? repo.markAllReadByGroup(userId, group.categories())
                : repo.markAllReadUnfiltered(userId);
        if (affected > 0) {
            registerReadStateBroadcast(userId);
        }
        return affected;
    }

    private void registerReadStateBroadcast(long userId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                wsBroadcaster.broadcastReadStateChanged(userId);
            }
        });
    }

    @Transactional(readOnly = true)
    public long unreadCount(long userId) {
        return repo.countByUserIdAndReadFalse(userId);
    }

    @Transactional(readOnly = true)
    public Map<NotificationCategory, Long> unreadCountByCategory(long userId) {
        Map<NotificationCategory, Long> out = new HashMap<>();
        for (Object[] row : repo.countUnreadByCategoryForUser(userId)) {
            out.put((NotificationCategory) row[0], ((Number) row[1]).longValue());
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Page<NotificationDto> listFor(long userId, NotificationGroup group,
                                          boolean unreadOnly, Pageable pageable) {
        Page<Notification> page = (group != null)
                ? repo.findForUserByGroup(userId, group.categories(), unreadOnly, pageable)
                : repo.findForUserUnfiltered(userId, unreadOnly, pageable);
        return page.map(NotificationDto::from);
    }

    private NotificationDto dtoFromUpsert(NotificationEvent event, UpsertResult result) {
        return new NotificationDto(
                result.id(), event.category(), event.category().getGroup(),
                event.title(), event.body(), event.data(), false,
                result.createdAt(), result.updatedAt()
        );
    }
}
