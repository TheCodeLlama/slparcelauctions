package com.slparcelauctions.backend.notification.slim;

import static org.assertj.core.api.Assertions.assertThat;

import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationGroup;
import com.slparcelauctions.backend.notification.slim.SlImChannelGate.Decision;
import com.slparcelauctions.backend.user.User;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SlImChannelGateTest {

    private final SlImChannelGate gate = new SlImChannelGate();

    @Test
    void noAvatar_isSkipNoAvatar_evenForSystem() {
        User u = userWithoutAvatar();
        assertThat(gate.decide(u, NotificationCategory.SYSTEM_ANNOUNCEMENT))
            .isEqualTo(Decision.SKIP_NO_AVATAR);
        assertThat(gate.decide(u, NotificationCategory.OUTBID))
            .isEqualTo(Decision.SKIP_NO_AVATAR);
    }

    @Test
    void systemCategory_bypassesMuteAndGroupPrefs_whenAvatarPresent() {
        User u = userWithAvatar();
        u.setNotifySlImMuted(true);
        u.setNotifySlIm(allGroupsOff());

        assertThat(gate.decide(u, NotificationCategory.SYSTEM_ANNOUNCEMENT))
            .isEqualTo(Decision.QUEUE_BYPASS_PREFS);
    }

    @Test
    void mutedUser_isSkipMuted_forNonSystemCategories() {
        User u = userWithAvatar();
        u.setNotifySlImMuted(true);
        u.setNotifySlIm(allGroupsOn());

        assertThat(gate.decide(u, NotificationCategory.OUTBID))
            .isEqualTo(Decision.SKIP_MUTED);
        assertThat(gate.decide(u, NotificationCategory.ESCROW_FUNDED))
            .isEqualTo(Decision.SKIP_MUTED);
    }

    @Test
    void groupOff_isSkipGroupDisabled() {
        User u = userWithAvatar();
        u.setNotifySlImMuted(false);
        Map<String, Object> prefs = allGroupsOn();
        prefs.put("bidding", false);
        u.setNotifySlIm(prefs);

        assertThat(gate.decide(u, NotificationCategory.OUTBID))
            .isEqualTo(Decision.SKIP_GROUP_DISABLED);
        // Other groups still queue:
        assertThat(gate.decide(u, NotificationCategory.ESCROW_FUNDED))
            .isEqualTo(Decision.QUEUE);
    }

    @Test
    void groupAbsent_isSkipGroupDisabled() {
        User u = userWithAvatar();
        u.setNotifySlImMuted(false);
        u.setNotifySlIm(new HashMap<>()); // no keys

        assertThat(gate.decide(u, NotificationCategory.OUTBID))
            .isEqualTo(Decision.SKIP_GROUP_DISABLED);
    }

    @Test
    void allGroupsOnUnmutedWithAvatar_isQueue() {
        User u = userWithAvatar();
        u.setNotifySlImMuted(false);
        u.setNotifySlIm(allGroupsOn());

        for (NotificationCategory c : NotificationCategory.values()) {
            if (c.getGroup() == NotificationGroup.SYSTEM) {
                assertThat(gate.decide(u, c)).isEqualTo(Decision.QUEUE_BYPASS_PREFS);
            } else {
                assertThat(gate.decide(u, c)).isEqualTo(Decision.QUEUE);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("matrixCases")
    void matrix(boolean hasAvatar, boolean muted, boolean groupOn,
                NotificationCategory category, Decision expected) {
        User u = hasAvatar ? userWithAvatar() : userWithoutAvatar();
        u.setNotifySlImMuted(muted);
        Map<String, Object> prefs = allGroupsOn();
        if (!groupOn) {
            prefs.put(category.getGroup().name().toLowerCase(), false);
        }
        u.setNotifySlIm(prefs);

        assertThat(gate.decide(u, category)).isEqualTo(expected);
    }

    static Stream<Arguments> matrixCases() {
        // (hasAvatar, muted, groupOn, category, expected)
        return Stream.of(
            // No avatar always wins
            Arguments.of(false, false, true,  NotificationCategory.OUTBID,            Decision.SKIP_NO_AVATAR),
            Arguments.of(false, true,  false, NotificationCategory.OUTBID,            Decision.SKIP_NO_AVATAR),
            Arguments.of(false, false, true,  NotificationCategory.SYSTEM_ANNOUNCEMENT, Decision.SKIP_NO_AVATAR),
            // SYSTEM bypasses prefs (when avatar present)
            Arguments.of(true,  true,  false, NotificationCategory.SYSTEM_ANNOUNCEMENT, Decision.QUEUE_BYPASS_PREFS),
            Arguments.of(true,  false, true,  NotificationCategory.SYSTEM_ANNOUNCEMENT, Decision.QUEUE_BYPASS_PREFS),
            // Muted wins over group prefs (non-SYSTEM)
            Arguments.of(true,  true,  true,  NotificationCategory.OUTBID,            Decision.SKIP_MUTED),
            // Group off when not muted
            Arguments.of(true,  false, false, NotificationCategory.OUTBID,            Decision.SKIP_GROUP_DISABLED),
            // Happy path
            Arguments.of(true,  false, true,  NotificationCategory.OUTBID,            Decision.QUEUE),
            Arguments.of(true,  false, true,  NotificationCategory.AUCTION_WON,       Decision.QUEUE),
            Arguments.of(true,  false, true,  NotificationCategory.ESCROW_FUNDED,     Decision.QUEUE)
        );
    }

    private User userWithoutAvatar() {
        User u = User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
            .email("u-" + UUID.randomUUID() + "@test.local")
            .passwordHash("hash")
            .build();
        u.setSlAvatarUuid(null);
        u.setNotifySlIm(allGroupsOn());
        return u;
    }

    private User userWithAvatar() {
        User u = userWithoutAvatar();
        u.setSlAvatarUuid(UUID.randomUUID());
        return u;
    }

    private Map<String, Object> allGroupsOn() {
        Map<String, Object> m = new HashMap<>();
        for (NotificationGroup g : NotificationGroup.values()) {
            m.put(g.name().toLowerCase(), true);
        }
        return m;
    }

    private Map<String, Object> allGroupsOff() {
        Map<String, Object> m = new HashMap<>();
        for (NotificationGroup g : NotificationGroup.values()) {
            m.put(g.name().toLowerCase(), false);
        }
        return m;
    }
}
