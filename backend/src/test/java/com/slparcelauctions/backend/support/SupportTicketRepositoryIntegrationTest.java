package com.slparcelauctions.backend.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Repository coverage for the two hot-path support-ticket queries:
 *
 * <ul>
 *   <li>{@link SupportTicketRepository#findByPublicId} - the controllers'
 *       detail-read entry point. Carries an {@code @EntityGraph} that
 *       eagerly fetches messages and their attachments so the mapper can
 *       run outside an open Hibernate session.</li>
 *   <li>{@link SupportTicketRepository#countByUserIdAndCreatedAtAfter} -
 *       powers the per-user 5/hour create rate limiter.</li>
 * </ul>
 *
 * <p>Conventions follow {@link com.slparcelauctions.backend.coupon.CouponRepositoryIntegrationTest}:
 * {@code @SpringBootTest} + {@code @Transactional} so each test rolls
 * back; quieted background schedulers via {@code @TestPropertySource}.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false",
        "slpa.realty.invitation-expiry.enabled=false"
})
@Transactional
class SupportTicketRepositoryIntegrationTest {

    @Autowired SupportTicketRepository ticketRepo;
    @Autowired UserRepository userRepo;

    @PersistenceContext EntityManager em;

    private User userA;
    private User userB;

    @BeforeEach
    void seed() {
        userA = userRepo.save(User.builder()
                .username("userA-" + shortId())
                .email("userA-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                .displayName("User A").verified(true).build());
        userB = userRepo.save(User.builder()
                .username("userB-" + shortId())
                .email("userB-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                .displayName("User B").verified(true).build());
    }

    @Test
    void findByPublicId_eagerlyLoadsMessagesAndAttachments() {
        OffsetDateTime now = OffsetDateTime.now();
        SupportTicket ticket = SupportTicket.builder()
                .user(userA)
                .subject("Help with my listing")
                .category(SupportTicketCategory.LISTING)
                .status(SupportTicketStatus.OPEN)
                .lastMessageAt(now)
                .lastMessageAuthor(SupportTicketAuthorRole.USER)
                .build();
        em.persist(ticket);

        SupportTicketMessage messageWithAttachments = SupportTicketMessage.builder()
                .ticket(ticket)
                .authorUser(userA)
                .authorRole(SupportTicketAuthorRole.USER)
                .body("Here are some screenshots.")
                .visibleToUser(true)
                .build();
        em.persist(messageWithAttachments);

        SupportTicketMessage replyMessage = SupportTicketMessage.builder()
                .ticket(ticket)
                .authorUser(userA)
                .authorRole(SupportTicketAuthorRole.USER)
                .body("Follow-up text-only message.")
                .visibleToUser(true)
                .build();
        em.persist(replyMessage);

        for (int i = 0; i < 3; i++) {
            SupportTicketAttachment att = SupportTicketAttachment.builder()
                    .message(messageWithAttachments)
                    .storageKey("support-attachments/" + userA.getPublicId() + "/img-" + i + ".png")
                    .mimeType("image/png")
                    .sizeBytes(1024 * (i + 1))
                    .width(800)
                    .height(600)
                    .build();
            em.persist(att);
        }

        em.flush();
        em.clear();

        SupportTicket loaded = ticketRepo.findByPublicId(ticket.getPublicId()).orElseThrow();

        assertThat(Hibernate.isInitialized(loaded.getMessages())).isTrue();
        assertThat(loaded.getMessages()).hasSize(2);

        SupportTicketMessage loadedWithAttachments = loaded.getMessages().stream()
                .filter(m -> m.getAttachments() != null && !m.getAttachments().isEmpty())
                .findFirst()
                .orElseThrow();
        assertThat(Hibernate.isInitialized(loadedWithAttachments.getAttachments())).isTrue();
        assertThat(loadedWithAttachments.getAttachments()).hasSize(3);
    }

    @Test
    void countByUserIdAndCreatedAtAfter_countsOnlyMatchingRows() {
        OffsetDateTime now = OffsetDateTime.now();

        // User A: two recent tickets.
        long recentA1 = persistTicket(userA, now);
        long recentA2 = persistTicket(userA, now);

        // User A: one ticket created 2 hours ago - should NOT be counted.
        long oldA = persistTicket(userA, now);
        backdateCreatedAt(oldA, now.minusHours(2));

        // User B: one recent ticket - belongs to a different user.
        persistTicket(userB, now);

        em.flush();
        em.clear();

        long count = ticketRepo.countByUserIdAndCreatedAtAfter(
                userA.getId(), now.minusHours(1));

        assertThat(count).isEqualTo(2L);
        // Sanity: the old A ticket still exists, just outside the window.
        assertThat(ticketRepo.findById(oldA)).isPresent();
        assertThat(ticketRepo.findById(recentA1)).isPresent();
        assertThat(ticketRepo.findById(recentA2)).isPresent();
    }

    private long persistTicket(User owner, OffsetDateTime lastMessageAt) {
        SupportTicket t = SupportTicket.builder()
                .user(owner)
                .subject("subj-" + shortId())
                .category(SupportTicketCategory.OTHER)
                .status(SupportTicketStatus.OPEN)
                .lastMessageAt(lastMessageAt)
                .lastMessageAuthor(SupportTicketAuthorRole.USER)
                .build();
        em.persist(t);
        em.flush();
        return t.getId();
    }

    private void backdateCreatedAt(long ticketId, OffsetDateTime when) {
        em.createNativeQuery(
                        "UPDATE support_tickets SET created_at = ?1 WHERE id = ?2")
                .setParameter(1, when)
                .setParameter(2, ticketId)
                .executeUpdate();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
