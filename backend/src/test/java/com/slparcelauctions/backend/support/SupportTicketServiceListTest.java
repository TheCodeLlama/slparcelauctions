package com.slparcelauctions.backend.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.support.dto.CreateSupportTicketRequest;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Plan Task 10 -- integration coverage for the
 * {@link SupportTicketService#listForUser} and
 * {@link SupportTicketService#listAdmin} Specification-based paginated
 * queries.
 *
 * <p>Mirrors the {@code @SpringBootTest + @ActiveProfiles("dev")} +
 * scheduler-mute pattern used by
 * {@link SupportTicketServiceAdminActionsTest}, with {@code @Transactional}
 * rollback per test. Tickets are seeded with a per-test unique subject
 * prefix so the {@code q} filter narrows results to the test's own rows
 * even against a shared dev DB.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.auction-end.enabled=false",
        "slpa.ownership-monitor.enabled=false",
        "slpa.escrow.ownership-monitor-job.enabled=false",
        "slpa.escrow.timeout-job.enabled=false",
        "slpa.escrow.command-dispatcher-job.enabled=false",
        "slpa.review.scheduler.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false",
        "slpa.realty.invitation-expiry.enabled=false"
})
@Transactional
class SupportTicketServiceListTest {

    @Autowired SupportTicketService service;
    @Autowired SupportTicketRepository ticketRepo;
    @Autowired UserRepository userRepo;

    @MockitoBean NotificationPublisher notifications;

    @PersistenceContext EntityManager em;

    private User submitter;
    private User otherUser;
    private User admin1;
    private String prefix;

    @BeforeEach
    void seed() {
        prefix = "LIST-TEST-" + UUID.randomUUID().toString().substring(0, 8);
        submitter = userRepo.save(User.builder()
                .username("submitter-" + shortId())
                .email("submitter-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("Submitter")
                .role(Role.USER)
                .verified(true)
                .build());
        otherUser = userRepo.save(User.builder()
                .username("user-other-" + shortId())
                .email("user-other-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("Other User")
                .role(Role.USER)
                .verified(true)
                .build());
        admin1 = userRepo.save(User.builder()
                .username("admin-one-" + shortId())
                .email("admin-one-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("Admin One")
                .role(Role.ADMIN)
                .verified(true)
                .build());
        em.flush();
    }

    // -- listForUser ----------------------------------------------------------

    @Test
    void listForUser_returns_only_owned_tickets() {
        SupportTicket mine1 = seedTicket(submitter, prefix + " mine 1",
                SupportTicketCategory.OTHER);
        SupportTicket mine2 = seedTicket(submitter, prefix + " mine 2",
                SupportTicketCategory.OTHER);
        seedTicket(otherUser, prefix + " not mine", SupportTicketCategory.OTHER);
        em.flush();

        // Narrow to the seeded prefix so we don't grade against unrelated dev rows.
        Page<SupportTicket> page = service.listForUser(
                submitter.getId(), null, prefix, PageRequest.of(0, 20));

        assertThat(page.getContent())
                .extracting(SupportTicket::getPublicId)
                .containsExactlyInAnyOrder(mine1.getPublicId(), mine2.getPublicId());
    }

    @Test
    void listForUser_status_filter_narrows() {
        SupportTicket open1 = seedTicket(submitter, prefix + " open one",
                SupportTicketCategory.OTHER);
        SupportTicket open2 = seedTicket(submitter, prefix + " open two",
                SupportTicketCategory.OTHER);
        SupportTicket resolved = seedTicket(submitter, prefix + " resolved one",
                SupportTicketCategory.OTHER);
        service.resolve(admin1.getId(), resolved.getPublicId());
        em.flush();

        Page<SupportTicket> openPage = service.listForUser(
                submitter.getId(), SupportTicketStatus.OPEN, prefix,
                PageRequest.of(0, 20));
        assertThat(openPage.getContent())
                .extracting(SupportTicket::getPublicId)
                .containsExactlyInAnyOrder(open1.getPublicId(), open2.getPublicId());

        Page<SupportTicket> resolvedPage = service.listForUser(
                submitter.getId(), SupportTicketStatus.RESOLVED, prefix,
                PageRequest.of(0, 20));
        assertThat(resolvedPage.getContent())
                .extracting(SupportTicket::getPublicId)
                .containsExactly(resolved.getPublicId());
    }

    @Test
    void listForUser_q_filter_subject_substring_case_insensitive() {
        SupportTicket walletTicket = seedTicket(submitter,
                prefix + " Wallet Topup Issue", SupportTicketCategory.WALLET);
        seedTicket(submitter, prefix + " Bidding Problem",
                SupportTicketCategory.BIDDING);
        em.flush();

        // Search lowercase substring against a mixed-case subject.
        Page<SupportTicket> page = service.listForUser(
                submitter.getId(), null, prefix + " wallet",
                PageRequest.of(0, 20));

        assertThat(page.getContent())
                .extracting(SupportTicket::getPublicId)
                .containsExactly(walletTicket.getPublicId());
    }

    // -- listAdmin ------------------------------------------------------------

    @Test
    void listAdmin_status_filter() {
        SupportTicket open = seedTicket(submitter, prefix + " open one",
                SupportTicketCategory.OTHER);
        SupportTicket resolved = seedTicket(submitter, prefix + " resolved one",
                SupportTicketCategory.OTHER);
        service.resolve(admin1.getId(), resolved.getPublicId());
        em.flush();

        Page<SupportTicket> openPage = service.listAdmin(
                SupportTicketStatus.OPEN, null, null, null, prefix,
                admin1.getId(), PageRequest.of(0, 20));
        assertThat(openPage.getContent())
                .extracting(SupportTicket::getPublicId)
                .containsExactly(open.getPublicId());

        Page<SupportTicket> resolvedPage = service.listAdmin(
                SupportTicketStatus.RESOLVED, null, null, null, prefix,
                admin1.getId(), PageRequest.of(0, 20));
        assertThat(resolvedPage.getContent())
                .extracting(SupportTicket::getPublicId)
                .containsExactly(resolved.getPublicId());
    }

    @Test
    void listAdmin_category_filter() {
        SupportTicket wallet = seedTicket(submitter, prefix + " wallet",
                SupportTicketCategory.WALLET);
        seedTicket(submitter, prefix + " bidding", SupportTicketCategory.BIDDING);
        em.flush();

        Page<SupportTicket> page = service.listAdmin(
                null, SupportTicketCategory.WALLET, null, null, prefix,
                admin1.getId(), PageRequest.of(0, 20));
        assertThat(page.getContent())
                .extracting(SupportTicket::getPublicId)
                .containsExactly(wallet.getPublicId());
    }

    @Test
    void listAdmin_lastAuthor_filter() {
        SupportTicket userLast = seedTicket(submitter, prefix + " user last",
                SupportTicketCategory.OTHER);
        // Newly created tickets default to lastMessageAuthor=USER. Flip one
        // to ADMIN to exercise the filter both ways.
        SupportTicket adminLast = seedTicket(submitter, prefix + " admin last",
                SupportTicketCategory.OTHER);
        adminLast.setLastMessageAuthor(SupportTicketAuthorRole.ADMIN);
        ticketRepo.save(adminLast);
        em.flush();

        Page<SupportTicket> userPage = service.listAdmin(
                null, null, null, SupportTicketAuthorRole.USER, prefix,
                admin1.getId(), PageRequest.of(0, 20));
        assertThat(userPage.getContent())
                .extracting(SupportTicket::getPublicId)
                .containsExactly(userLast.getPublicId());

        Page<SupportTicket> adminPage = service.listAdmin(
                null, null, null, SupportTicketAuthorRole.ADMIN, prefix,
                admin1.getId(), PageRequest.of(0, 20));
        assertThat(adminPage.getContent())
                .extracting(SupportTicket::getPublicId)
                .containsExactly(adminLast.getPublicId());
    }

    @Test
    void listAdmin_assignee_mine() {
        SupportTicket mineAssigned = seedTicket(submitter, prefix + " mine",
                SupportTicketCategory.OTHER);
        mineAssigned.setAssignedAdminId(admin1.getId());
        ticketRepo.save(mineAssigned);
        seedTicket(submitter, prefix + " unassigned", SupportTicketCategory.OTHER);
        em.flush();

        Page<SupportTicket> page = service.listAdmin(
                null, null, "mine", null, prefix,
                admin1.getId(), PageRequest.of(0, 20));

        assertThat(page.getContent())
                .extracting(SupportTicket::getPublicId)
                .containsExactly(mineAssigned.getPublicId());
    }

    @Test
    void listAdmin_assignee_unassigned() {
        SupportTicket assigned = seedTicket(submitter, prefix + " assigned",
                SupportTicketCategory.OTHER);
        assigned.setAssignedAdminId(admin1.getId());
        ticketRepo.save(assigned);
        SupportTicket unassigned = seedTicket(submitter, prefix + " unassigned",
                SupportTicketCategory.OTHER);
        em.flush();

        Page<SupportTicket> page = service.listAdmin(
                null, null, "unassigned", null, prefix,
                admin1.getId(), PageRequest.of(0, 20));

        assertThat(page.getContent())
                .extracting(SupportTicket::getPublicId)
                .containsExactly(unassigned.getPublicId());
    }

    @Test
    void listAdmin_assignee_userPublicId() {
        SupportTicket mine = seedTicket(submitter, prefix + " by submitter",
                SupportTicketCategory.OTHER);
        seedTicket(otherUser, prefix + " by other", SupportTicketCategory.OTHER);
        em.flush();

        Page<SupportTicket> page = service.listAdmin(
                null, null, submitter.getPublicId().toString(), null, prefix,
                admin1.getId(), PageRequest.of(0, 20));

        assertThat(page.getContent())
                .extracting(SupportTicket::getPublicId)
                .containsExactly(mine.getPublicId());
    }

    @Test
    void listAdmin_q_filter() {
        SupportTicket wallet = seedTicket(submitter,
                prefix + " Wallet Topup Issue", SupportTicketCategory.WALLET);
        seedTicket(submitter, prefix + " Bidding Problem",
                SupportTicketCategory.BIDDING);
        em.flush();

        Page<SupportTicket> page = service.listAdmin(
                null, null, null, null, prefix + " wallet",
                admin1.getId(), PageRequest.of(0, 20));

        assertThat(page.getContent())
                .extracting(SupportTicket::getPublicId)
                .containsExactly(wallet.getPublicId());
    }

    @Test
    void listAdmin_combined_filters() {
        // Target row: OPEN + BIDDING + lastAuthor=USER + matches q.
        SupportTicket target = seedTicket(submitter,
                prefix + " bidding open user-last",
                SupportTicketCategory.BIDDING);
        // Different category -- excluded.
        seedTicket(submitter, prefix + " wallet open user-last",
                SupportTicketCategory.WALLET);
        // Same category but resolved -- excluded.
        SupportTicket resolved = seedTicket(submitter,
                prefix + " bidding resolved", SupportTicketCategory.BIDDING);
        service.resolve(admin1.getId(), resolved.getPublicId());
        // Same category + open but admin-last -- excluded.
        SupportTicket adminLast = seedTicket(submitter,
                prefix + " bidding admin-last",
                SupportTicketCategory.BIDDING);
        adminLast.setLastMessageAuthor(SupportTicketAuthorRole.ADMIN);
        ticketRepo.save(adminLast);
        em.flush();

        Page<SupportTicket> page = service.listAdmin(
                SupportTicketStatus.OPEN, SupportTicketCategory.BIDDING,
                null, SupportTicketAuthorRole.USER,
                prefix + " bidding open user-last",
                admin1.getId(), PageRequest.of(0, 20));

        assertThat(page.getContent())
                .extracting(SupportTicket::getPublicId)
                .containsExactly(target.getPublicId());
    }

    // -- helpers --------------------------------------------------------------

    private SupportTicket seedTicket(User owner, String subject,
                                     SupportTicketCategory category) {
        return service.createTicket(owner.getId(), new CreateSupportTicketRequest(
                subject, category, "body for " + subject, null));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
