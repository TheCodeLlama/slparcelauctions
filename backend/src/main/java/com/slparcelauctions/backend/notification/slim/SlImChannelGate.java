package com.slparcelauctions.backend.notification.slim;

import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationGroup;
import com.slparcelauctions.backend.user.User;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Pure decision function for "should we queue an SL IM for this user/category?"
 *
 * <p>Stateless. Returns a discriminated {@link Decision} so callers can log the
 * reason at DEBUG, which makes "I didn't get an IM" support tickets resolvable
 * from logs alone without DB archaeology.
 *
 * <p>Order of checks (no-avatar is the universal floor — applies even to SYSTEM):
 * <ol>
 *   <li>{@link User#getSlAvatarUuid()} == null  → {@link Decision#SKIP_NO_AVATAR}</li>
 *   <li>category.group == SYSTEM                → {@link Decision#QUEUE_BYPASS_PREFS}</li>
 *   <li>{@link User#isNotifySlImMuted()} true   → {@link Decision#SKIP_MUTED}</li>
 *   <li>notifySlIm[group] false or absent       → {@link Decision#SKIP_GROUP_DISABLED}</li>
 *   <li>otherwise                               → {@link Decision#QUEUE}</li>
 * </ol>
 */
@Component
public class SlImChannelGate {

    public enum Decision {
        QUEUE,
        QUEUE_BYPASS_PREFS,
        SKIP_NO_AVATAR,
        SKIP_MUTED,
        SKIP_GROUP_DISABLED
    }

    public Decision decide(User user, NotificationCategory category) {
        if (user.getSlAvatarUuid() == null) {
            return Decision.SKIP_NO_AVATAR;
        }

        boolean isSystem = category.getGroup() == NotificationGroup.SYSTEM;
        if (isSystem) {
            return Decision.QUEUE_BYPASS_PREFS;
        }

        if (Boolean.TRUE.equals(user.getNotifySlImMuted())) {
            return Decision.SKIP_MUTED;
        }

        Map<String, Object> prefs = user.getNotifySlIm();
        String groupKey = category.getGroup().name().toLowerCase();
        Object groupVal = prefs == null ? null : prefs.get(groupKey);
        boolean groupOn = Boolean.TRUE.equals(groupVal);
        if (!groupOn) {
            return Decision.SKIP_GROUP_DISABLED;
        }

        return Decision.QUEUE;
    }
}
