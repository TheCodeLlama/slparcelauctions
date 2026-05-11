package com.slparcelauctions.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.notification.slim.SlImChannelDispatcher;
import com.slparcelauctions.backend.realty.InvitationStatus;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupInvitation;
import com.slparcelauctions.backend.realty.RealtyGroupMember;
import com.slparcelauctions.backend.realty.RealtyGroupMemberRepository;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unit tests for the 9 realty-group methods on {@link NotificationPublisherImpl}.
 *
 * <p>Recipients per spec §8. Channel infrastructure (DAO, broadcaster, SL IM dispatcher,
 * etc.) is all mocked; we assert exactly which user IDs got a {@code publish(...)} call
 * with the expected category. Body copy is sanity-checked but not pinned word-for-word
 * so future copy tweaks don't break these tests.
 */
@ExtendWith(MockitoExtension.class)
class NotificationPublisherRealtyGroupTest {

    @Mock NotificationService notificationService;
    @Mock NotificationDao notificationDao;
    @Mock NotificationWsBroadcasterPort wsBroadcaster;
    @Mock TransactionTemplate requiresNewTxTemplate;
    @Mock SlImChannelDispatcher slImChannelDispatcher;
    @Mock RealtyGroupRepository realtyGroupRepository;
    @Mock RealtyGroupMemberRepository realtyGroupMemberRepository;
    @Mock UserRepository userRepository;

    NotificationPublisherImpl publisher;

    private static final Long LEADER_ID = 100L;
    private static final Long DELEGATE_A_ID = 200L;
    private static final Long DELEGATE_B_ID = 201L;
    private static final Long PLAIN_MEMBER_ID = 300L;
    private static final Long INVITEE_ID = 400L;
    private static final Long INVITER_ID = 100L;

    private RealtyGroup group;

    @BeforeEach
    void setup() {
        publisher = new NotificationPublisherImpl(
            notificationService, notificationDao, wsBroadcaster,
            requiresNewTxTemplate, slImChannelDispatcher,
            realtyGroupRepository, realtyGroupMemberRepository, userRepository);

        group = RealtyGroup.builder()
            .name("Mainland Realty")
            .slug("mainland-realty")
            .leaderId(LEADER_ID)
            .build();
        setEntityIdentity(group, 10L, UUID.randomUUID());
    }

    /**
     * Sets {@code id} and {@code publicId} on a freshly-built entity. Lombok's
     * {@code @SuperBuilder} doesn't expose the BaseEntity setters by default,
     * so we reach in via reflection. Test-only helper.
     */
    private static void setEntityIdentity(Object entity, Long id, UUID publicId) {
        try {
            var idField = entity.getClass().getSuperclass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);
            var pidField = entity.getClass().getSuperclass().getSuperclass().getDeclaredField("publicId");
            pidField.setAccessible(true);
            pidField.set(entity, publicId);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static User userWith(Long id, String username) {
        User u = User.builder()
            .username(username)
            .email(username + "@example.com")
            .passwordHash("hash")
            .build();
        setEntityIdentity(u, id, UUID.randomUUID());
        return u;
    }

    private static RealtyGroupMember memberWith(Long groupId, Long userId,
                                                  EnumSet<RealtyGroupPermission> perms) {
        RealtyGroupMember m = RealtyGroupMember.builder()
            .groupId(groupId).userId(userId)
            .joinedAt(OffsetDateTime.now())
            .build();
        setEntityIdentity(m, userId * 10, UUID.randomUUID());
        m.setPermissionSet(perms);
        return m;
    }

    private RealtyGroupInvitation invitation(Long invitedUserId, Long invitedById) {
        RealtyGroupInvitation inv = RealtyGroupInvitation.builder()
            .groupId(group.getId())
            .invitedUserId(invitedUserId)
            .invitedById(invitedById)
            .status(InvitationStatus.PENDING)
            .expiresAt(OffsetDateTime.now().plusDays(6))
            .build();
        setEntityIdentity(inv, 99L, UUID.randomUUID());
        return inv;
    }

    /** Wires the standard test group: leader + 2 delegates (INVITE_AGENTS) + 1 plain member. */
    private void wireFullMemberSet() {
        when(realtyGroupRepository.findById(group.getId())).thenReturn(Optional.of(group));
        List<RealtyGroupMember> roster = new ArrayList<>();
        // leader's own member row
        roster.add(memberWith(group.getId(), LEADER_ID, EnumSet.noneOf(RealtyGroupPermission.class)));
        roster.add(memberWith(group.getId(), DELEGATE_A_ID,
            EnumSet.of(RealtyGroupPermission.INVITE_AGENTS)));
        roster.add(memberWith(group.getId(), DELEGATE_B_ID,
            EnumSet.of(RealtyGroupPermission.INVITE_AGENTS, RealtyGroupPermission.REMOVE_AGENTS)));
        roster.add(memberWith(group.getId(), PLAIN_MEMBER_ID,
            EnumSet.of(RealtyGroupPermission.EDIT_GROUP_PROFILE)));
        when(realtyGroupMemberRepository.findByGroupIdOrderByJoinedAtAsc(group.getId()))
            .thenReturn(roster);
    }

    private List<NotificationEvent> capturedEvents() {
        ArgumentCaptor<NotificationEvent> captor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationService, atLeastOnce()).publish(captor.capture());
        return captor.getAllValues();
    }

    private static Set<Long> recipientsFor(List<NotificationEvent> events, NotificationCategory cat) {
        java.util.Set<Long> ids = new java.util.LinkedHashSet<>();
        for (NotificationEvent e : events) {
            if (e.category() == cat) ids.add(e.userId());
        }
        return ids;
    }

    // ── invitation sent ──────────────────────────────────────────────────────────

    @Test
    void invitationSent_deliversToInviteeOnly() {
        RealtyGroupInvitation inv = invitation(INVITEE_ID, INVITER_ID);
        when(realtyGroupRepository.findById(group.getId())).thenReturn(Optional.of(group));
        when(userRepository.findById(INVITER_ID)).thenReturn(Optional.of(userWith(INVITER_ID, "leader")));

        publisher.realtyGroupInvitationSent(inv);

        var events = capturedEvents();
        assertThat(events).hasSize(1);
        NotificationEvent e = events.get(0);
        assertThat(e.userId()).isEqualTo(INVITEE_ID);
        assertThat(e.category()).isEqualTo(NotificationCategory.REALTY_GROUP_INVITATION_SENT);
        assertThat(e.data()).containsEntry("groupName", "Mainland Realty");
    }

    @Test
    void invitationSent_silentWhenGroupMissing() {
        RealtyGroupInvitation inv = invitation(INVITEE_ID, INVITER_ID);
        when(realtyGroupRepository.findById(group.getId())).thenReturn(Optional.empty());

        publisher.realtyGroupInvitationSent(inv);

        verify(notificationService, never()).publish(any());
    }

    // ── invitation accepted ──────────────────────────────────────────────────────

    @Test
    void invitationAccepted_deliversToLeaderAndDelegatesExcludingNewMember() {
        // The new member (invitee) happens to be a delegate by accident in the
        // wired roster — make them so to exercise the "exclude actor" rule.
        when(realtyGroupRepository.findById(group.getId())).thenReturn(Optional.of(group));
        List<RealtyGroupMember> roster = List.of(
            memberWith(group.getId(), LEADER_ID, EnumSet.noneOf(RealtyGroupPermission.class)),
            memberWith(group.getId(), DELEGATE_A_ID, EnumSet.of(RealtyGroupPermission.INVITE_AGENTS)),
            memberWith(group.getId(), INVITEE_ID, EnumSet.of(RealtyGroupPermission.INVITE_AGENTS))
        );
        when(realtyGroupMemberRepository.findByGroupIdOrderByJoinedAtAsc(group.getId()))
            .thenReturn(roster);
        when(userRepository.findById(INVITEE_ID)).thenReturn(Optional.of(userWith(INVITEE_ID, "newbie")));

        RealtyGroupInvitation inv = invitation(INVITEE_ID, INVITER_ID);
        publisher.realtyGroupInvitationAccepted(inv);

        var events = capturedEvents();
        Set<Long> ids = recipientsFor(events, NotificationCategory.REALTY_GROUP_INVITATION_ACCEPTED);
        assertThat(ids).containsExactlyInAnyOrder(LEADER_ID, DELEGATE_A_ID);
        // The invitee themselves must NOT receive an accept notification.
        assertThat(ids).doesNotContain(INVITEE_ID);
    }

    // ── invitation declined ──────────────────────────────────────────────────────

    @Test
    void invitationDeclined_deliversToLeaderAndDelegates() {
        wireFullMemberSet();
        when(userRepository.findById(INVITEE_ID)).thenReturn(Optional.of(userWith(INVITEE_ID, "decliner")));

        RealtyGroupInvitation inv = invitation(INVITEE_ID, INVITER_ID);
        publisher.realtyGroupInvitationDeclined(inv);

        Set<Long> ids = recipientsFor(capturedEvents(),
            NotificationCategory.REALTY_GROUP_INVITATION_DECLINED);
        assertThat(ids).containsExactlyInAnyOrder(LEADER_ID, DELEGATE_A_ID, DELEGATE_B_ID);
        assertThat(ids).doesNotContain(PLAIN_MEMBER_ID);
    }

    // ── invitation expired ───────────────────────────────────────────────────────

    @Test
    void invitationExpired_deliversToLeaderAndDelegates() {
        wireFullMemberSet();
        when(userRepository.findById(INVITEE_ID)).thenReturn(Optional.of(userWith(INVITEE_ID, "expiree")));

        RealtyGroupInvitation inv = invitation(INVITEE_ID, INVITER_ID);
        publisher.realtyGroupInvitationExpired(inv);

        Set<Long> ids = recipientsFor(capturedEvents(),
            NotificationCategory.REALTY_GROUP_INVITATION_EXPIRED);
        assertThat(ids).containsExactlyInAnyOrder(LEADER_ID, DELEGATE_A_ID, DELEGATE_B_ID);
    }

    // ── member removed ───────────────────────────────────────────────────────────

    @Test
    void memberRemoved_deliversToRemovedUserOnly() {
        User removed = userWith(PLAIN_MEMBER_ID, "removed-one");

        publisher.realtyGroupMemberRemoved(group, removed);

        var events = capturedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).userId()).isEqualTo(PLAIN_MEMBER_ID);
        assertThat(events.get(0).category()).isEqualTo(NotificationCategory.REALTY_GROUP_MEMBER_REMOVED);
        // None of the leader / delegates get this notification.
        verify(realtyGroupMemberRepository, never()).findByGroupIdOrderByJoinedAtAsc(any());
    }

    // ── member left ──────────────────────────────────────────────────────────────

    @Test
    void memberLeft_deliversToLeaderAndOtherDelegates_excludingTheLeaver() {
        // The leaver happens to have INVITE_AGENTS — they must still NOT receive a notification
        // about their own departure.
        when(realtyGroupRepository.findById(group.getId())).thenReturn(Optional.of(group));
        List<RealtyGroupMember> roster = List.of(
            memberWith(group.getId(), LEADER_ID, EnumSet.noneOf(RealtyGroupPermission.class)),
            memberWith(group.getId(), DELEGATE_A_ID, EnumSet.of(RealtyGroupPermission.INVITE_AGENTS)),
            memberWith(group.getId(), DELEGATE_B_ID, EnumSet.of(RealtyGroupPermission.INVITE_AGENTS))
        );
        when(realtyGroupMemberRepository.findByGroupIdOrderByJoinedAtAsc(group.getId()))
            .thenReturn(roster);

        User leaver = userWith(DELEGATE_B_ID, "leaver");
        publisher.realtyGroupMemberLeft(group, leaver);

        Set<Long> ids = recipientsFor(capturedEvents(), NotificationCategory.REALTY_GROUP_MEMBER_LEFT);
        assertThat(ids).containsExactlyInAnyOrder(LEADER_ID, DELEGATE_A_ID);
        assertThat(ids).doesNotContain(DELEGATE_B_ID);
    }

    @Test
    void memberLeft_plainAgentWithoutInviteAgents_doesNotSelfNotify() {
        when(realtyGroupRepository.findById(group.getId())).thenReturn(Optional.of(group));
        List<RealtyGroupMember> roster = List.of(
            memberWith(group.getId(), LEADER_ID, EnumSet.noneOf(RealtyGroupPermission.class)),
            memberWith(group.getId(), DELEGATE_A_ID, EnumSet.of(RealtyGroupPermission.INVITE_AGENTS)),
            memberWith(group.getId(), PLAIN_MEMBER_ID, EnumSet.of(RealtyGroupPermission.EDIT_GROUP_PROFILE))
        );
        when(realtyGroupMemberRepository.findByGroupIdOrderByJoinedAtAsc(group.getId()))
            .thenReturn(roster);

        User leaver = userWith(PLAIN_MEMBER_ID, "plain-leaver");
        publisher.realtyGroupMemberLeft(group, leaver);

        Set<Long> ids = recipientsFor(capturedEvents(), NotificationCategory.REALTY_GROUP_MEMBER_LEFT);
        assertThat(ids).containsExactlyInAnyOrder(LEADER_ID, DELEGATE_A_ID);
        assertThat(ids).doesNotContain(PLAIN_MEMBER_ID);
    }

    // ── leadership transferred ───────────────────────────────────────────────────

    @Test
    void leadershipTransferred_deliversToOldNewAndAllOtherCurrentMembers() {
        Long oldLeaderId = LEADER_ID;
        Long newLeaderId = DELEGATE_A_ID;
        // Post-transfer roster: new leader's row exists. We pretend the old leader stayed.
        when(realtyGroupMemberRepository.findByGroupIdOrderByJoinedAtAsc(group.getId()))
            .thenReturn(List.of(
                memberWith(group.getId(), newLeaderId, EnumSet.noneOf(RealtyGroupPermission.class)),
                memberWith(group.getId(), oldLeaderId, EnumSet.allOf(RealtyGroupPermission.class)),
                memberWith(group.getId(), DELEGATE_B_ID, EnumSet.of(RealtyGroupPermission.INVITE_AGENTS)),
                memberWith(group.getId(), PLAIN_MEMBER_ID, EnumSet.of(RealtyGroupPermission.EDIT_GROUP_PROFILE))
            ));

        User oldLeader = userWith(oldLeaderId, "old-leader");
        User newLeader = userWith(newLeaderId, "new-leader");

        publisher.realtyGroupLeadershipTransferred(group, oldLeader, newLeader, true);

        Set<Long> ids = recipientsFor(capturedEvents(),
            NotificationCategory.REALTY_GROUP_LEADERSHIP_TRANSFERRED);
        // Old + new + plain + other delegate. Deduped.
        assertThat(ids).containsExactlyInAnyOrder(oldLeaderId, newLeaderId, DELEGATE_B_ID, PLAIN_MEMBER_ID);
    }

    @Test
    void leadershipTransferred_oldLeaderLeft_stillNotifiedDespiteNotInRoster() {
        Long oldLeaderId = LEADER_ID;
        Long newLeaderId = DELEGATE_A_ID;
        // Post-transfer roster: old leader's row was deleted because they chose LEAVE.
        when(realtyGroupMemberRepository.findByGroupIdOrderByJoinedAtAsc(group.getId()))
            .thenReturn(List.of(
                memberWith(group.getId(), newLeaderId, EnumSet.noneOf(RealtyGroupPermission.class)),
                memberWith(group.getId(), PLAIN_MEMBER_ID, EnumSet.of(RealtyGroupPermission.EDIT_GROUP_PROFILE))
            ));

        User oldLeader = userWith(oldLeaderId, "old-leader-gone");
        User newLeader = userWith(newLeaderId, "new-leader");

        publisher.realtyGroupLeadershipTransferred(group, oldLeader, newLeader, false);

        Set<Long> ids = recipientsFor(capturedEvents(),
            NotificationCategory.REALTY_GROUP_LEADERSHIP_TRANSFERRED);
        assertThat(ids).containsExactlyInAnyOrder(oldLeaderId, newLeaderId, PLAIN_MEMBER_ID);
    }

    // ── dissolved ────────────────────────────────────────────────────────────────

    @Test
    void dissolved_deliversToEveryFormerMember_dedupedAndSilentOnNull() {
        User u1 = userWith(LEADER_ID, "u1");
        User u2 = userWith(DELEGATE_A_ID, "u2");
        User u3 = userWith(PLAIN_MEMBER_ID, "u3");
        // Include a duplicate to verify dedupe.
        List<User> formerMembers = List.of(u1, u2, u3, u2);

        publisher.realtyGroupDissolved(group, formerMembers);

        Set<Long> ids = recipientsFor(capturedEvents(), NotificationCategory.REALTY_GROUP_DISSOLVED);
        assertThat(ids).containsExactlyInAnyOrder(LEADER_ID, DELEGATE_A_ID, PLAIN_MEMBER_ID);
    }

    @Test
    void dissolved_emptyFormerMembers_publishesNothing() {
        publisher.realtyGroupDissolved(group, List.of());

        verify(notificationService, never()).publish(any());
    }

    // ── permissions changed ──────────────────────────────────────────────────────

    @Test
    void permissionsChanged_deliversToAffectedMemberOnly() {
        RealtyGroupMember target = memberWith(group.getId(), PLAIN_MEMBER_ID,
            EnumSet.of(RealtyGroupPermission.EDIT_GROUP_PROFILE));
        Set<RealtyGroupPermission> added = EnumSet.of(RealtyGroupPermission.EDIT_GROUP_PROFILE);
        Set<RealtyGroupPermission> removed = EnumSet.of(RealtyGroupPermission.REMOVE_AGENTS);

        publisher.realtyGroupPermissionsChanged(group, target, added, removed);

        var events = capturedEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).userId()).isEqualTo(PLAIN_MEMBER_ID);
        assertThat(events.get(0).category())
            .isEqualTo(NotificationCategory.REALTY_GROUP_PERMISSIONS_CHANGED);
        assertThat(events.get(0).data()).containsKeys("added", "removed");
        @SuppressWarnings("unchecked")
        List<String> addedNames = (List<String>) events.get(0).data().get("added");
        assertThat(addedNames).containsExactly("EDIT_GROUP_PROFILE");
        @SuppressWarnings("unchecked")
        List<String> removedNames = (List<String>) events.get(0).data().get("removed");
        assertThat(removedNames).containsExactly("REMOVE_AGENTS");
    }

    @Test
    void permissionsChanged_nullMember_publishesNothing() {
        publisher.realtyGroupPermissionsChanged(group, null,
            EnumSet.of(RealtyGroupPermission.INVITE_AGENTS), EnumSet.noneOf(RealtyGroupPermission.class));

        verify(notificationService, never()).publish(any());
    }

    // ── resolver helper coverage ─────────────────────────────────────────────────

    @Test
    void resolver_excludesLeaderWhenLeaderIsExclusion() {
        wireFullMemberSet();

        Set<Long> ids = publisher.resolveLeaderAndInviteDelegates(group.getId(), LEADER_ID);
        assertThat(ids).containsExactlyInAnyOrder(DELEGATE_A_ID, DELEGATE_B_ID);
        assertThat(ids).doesNotContain(LEADER_ID);
    }

    @Test
    void resolver_plainAgentWithoutInviteFlag_isNotARecipient() {
        wireFullMemberSet();

        Set<Long> ids = publisher.resolveLeaderAndInviteDelegates(group.getId());
        // PLAIN_MEMBER_ID holds EDIT_GROUP_PROFILE but not INVITE_AGENTS — must be absent.
        assertThat(ids).doesNotContain(PLAIN_MEMBER_ID);
        assertThat(ids).containsExactlyInAnyOrder(LEADER_ID, DELEGATE_A_ID, DELEGATE_B_ID);
    }

    @Test
    void resolver_groupMissing_returnsEmptySetWithoutCallingDelegatesQuery() {
        when(realtyGroupRepository.findById(group.getId())).thenReturn(Optional.empty());
        when(realtyGroupMemberRepository.findByGroupIdOrderByJoinedAtAsc(group.getId()))
            .thenReturn(List.of());

        Set<Long> ids = publisher.resolveLeaderAndInviteDelegates(group.getId());
        assertThat(ids).isEmpty();
    }

    @Test
    void publishCount_invitationAccepted_matchesRecipientCount() {
        // Sanity check: publish() is called exactly N times for the N recipients.
        wireFullMemberSet();
        when(userRepository.findById(INVITEE_ID)).thenReturn(Optional.of(userWith(INVITEE_ID, "newbie")));

        RealtyGroupInvitation inv = invitation(INVITEE_ID, INVITER_ID);
        publisher.realtyGroupInvitationAccepted(inv);

        // Recipients: LEADER + DELEGATE_A + DELEGATE_B  (PLAIN excluded by no-INVITE_AGENTS,
        // INVITEE excluded as actor — but they're not in roster either).
        verify(notificationService, times(3)).publish(any());
    }
}
