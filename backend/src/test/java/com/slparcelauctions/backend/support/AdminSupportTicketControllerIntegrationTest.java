package com.slparcelauctions.backend.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.support.dto.AdminReplyRequest;
import com.slparcelauctions.backend.support.dto.AssignTicketRequest;
import com.slparcelauctions.backend.support.dto.PatchTicketRequest;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Integration coverage for {@link AdminSupportTicketController}. Spins
 * the full Spring context (auth chain + slice advice + JPA) and exercises
 * every admin endpoint via {@link MockMvc} with a real admin JWT.
 *
 * <p>{@link ObjectStorageService} is mocked so the support context wires
 * cleanly even though attachment promotion is not exercised here.
 * {@link NotificationPublisher} is mocked so we can verify which
 * categories fire (and which do not) per endpoint without spinning up
 * the real DAO + WebSocket broadcaster.
 */
@SpringBootTest
@AutoConfigureMockMvc
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
class AdminSupportTicketControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepo;
    @Autowired SupportTicketRepository ticketRepo;
    @Autowired SupportTicketMessageRepository messageRepo;
    @PersistenceContext EntityManager em;

    @MockitoBean ObjectStorageService storage;
    @MockitoBean NotificationPublisher notifications;

    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private User submitter;
    private User admin;
    private User otherAdmin;
    private User regularUser;
    private String adminJwt;
    private String userJwt;

    @BeforeEach
    void seed() {
        submitter = userRepo.save(User.builder()
                .username("submitter-" + shortId())
                .email("submitter-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                .displayName("Submitter").role(Role.USER).verified(true).build());
        admin = userRepo.save(User.builder()
                .username("admin-" + shortId())
                .email("admin-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                .displayName("Admin").role(Role.ADMIN).verified(true).build());
        otherAdmin = userRepo.save(User.builder()
                .username("otheradmin-" + shortId())
                .email("otheradmin-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                .displayName("Other Admin").role(Role.ADMIN).verified(true).build());
        regularUser = userRepo.save(User.builder()
                .username("nonadmin-" + shortId())
                .email("nonadmin-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                .displayName("Non-admin").role(Role.USER).verified(true).build());

        adminJwt = jwtService.issueAccessToken(new AuthPrincipal(
                admin.getId(), admin.getPublicId(), admin.getEmail(), 0L, Role.ADMIN));
        userJwt = jwtService.issueAccessToken(new AuthPrincipal(
                regularUser.getId(), regularUser.getPublicId(), regularUser.getEmail(),
                0L, Role.USER));

        reset(notifications);
    }

    /* ============================================================ */
    /* GET /api/v1/admin/support-tickets - queue + filters          */
    /* ============================================================ */

    @Test
    void queue_noFilter_returnsAllTicketsPaged() throws Exception {
        saveTicket(submitter, "First subject", SupportTicketStatus.OPEN,
                SupportTicketCategory.ACCOUNT, null, SupportTicketAuthorRole.USER);
        saveTicket(submitter, "Second subject", SupportTicketStatus.RESOLVED,
                SupportTicketCategory.BIDDING, admin.getId(), SupportTicketAuthorRole.ADMIN);
        em.flush();

        mockMvc.perform(get("/api/v1/admin/support-tickets")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void queue_statusOpen_narrows() throws Exception {
        SupportTicket open = saveTicket(submitter, "Open one", SupportTicketStatus.OPEN,
                SupportTicketCategory.ACCOUNT, null, SupportTicketAuthorRole.USER);
        saveTicket(submitter, "Resolved one", SupportTicketStatus.RESOLVED,
                SupportTicketCategory.BIDDING, admin.getId(), SupportTicketAuthorRole.ADMIN);
        em.flush();

        mockMvc.perform(get("/api/v1/admin/support-tickets")
                        .param("status", "OPEN")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].publicId").value(open.getPublicId().toString()));
    }

    @Test
    void queue_categoryBidding_narrows() throws Exception {
        SupportTicket bidding = saveTicket(submitter, "Bidding ticket", SupportTicketStatus.OPEN,
                SupportTicketCategory.BIDDING, null, SupportTicketAuthorRole.USER);
        saveTicket(submitter, "Account ticket", SupportTicketStatus.OPEN,
                SupportTicketCategory.ACCOUNT, null, SupportTicketAuthorRole.USER);
        em.flush();

        mockMvc.perform(get("/api/v1/admin/support-tickets")
                        .param("category", "BIDDING")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].publicId").value(bidding.getPublicId().toString()));
    }

    @Test
    void queue_assigneeMine_narrowsToCallerAssignments() throws Exception {
        SupportTicket mine = saveTicket(submitter, "Mine", SupportTicketStatus.OPEN,
                SupportTicketCategory.ACCOUNT, admin.getId(), SupportTicketAuthorRole.USER);
        saveTicket(submitter, "Theirs", SupportTicketStatus.OPEN,
                SupportTicketCategory.ACCOUNT, otherAdmin.getId(), SupportTicketAuthorRole.USER);
        saveTicket(submitter, "Unassigned", SupportTicketStatus.OPEN,
                SupportTicketCategory.ACCOUNT, null, SupportTicketAuthorRole.USER);
        em.flush();

        mockMvc.perform(get("/api/v1/admin/support-tickets")
                        .param("assignee", "mine")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].publicId").value(mine.getPublicId().toString()));
    }

    @Test
    void queue_lastAuthorUser_narrows() throws Exception {
        SupportTicket userLast = saveTicket(submitter, "User last", SupportTicketStatus.OPEN,
                SupportTicketCategory.ACCOUNT, null, SupportTicketAuthorRole.USER);
        saveTicket(submitter, "Admin last", SupportTicketStatus.OPEN,
                SupportTicketCategory.ACCOUNT, null, SupportTicketAuthorRole.ADMIN);
        em.flush();

        mockMvc.perform(get("/api/v1/admin/support-tickets")
                        .param("last_author", "USER")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].publicId").value(userLast.getPublicId().toString()));
    }

    @Test
    void queue_qFilter_narrowsBySubjectSubstring() throws Exception {
        String unique = "needleQ" + shortId();
        SupportTicket match = saveTicket(submitter, "Subject containing " + unique + " here",
                SupportTicketStatus.OPEN, SupportTicketCategory.ACCOUNT, null,
                SupportTicketAuthorRole.USER);
        saveTicket(submitter, "Unrelated", SupportTicketStatus.OPEN,
                SupportTicketCategory.ACCOUNT, null, SupportTicketAuthorRole.USER);
        em.flush();

        mockMvc.perform(get("/api/v1/admin/support-tickets")
                        .param("q", unique)
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].publicId").value(match.getPublicId().toString()));
    }

    /* ============================================================ */
    /* GET /api/v1/admin/support-tickets/queue-stats                */
    /* ============================================================ */

    @Test
    void queueStats_returnsCorrectCounts() throws Exception {
        // 2 open with USER as last author (need admin reply)
        saveTicket(submitter, "Open user-last 1", SupportTicketStatus.OPEN,
                SupportTicketCategory.ACCOUNT, null, SupportTicketAuthorRole.USER);
        saveTicket(submitter, "Open user-last 2", SupportTicketStatus.OPEN,
                SupportTicketCategory.ACCOUNT, null, SupportTicketAuthorRole.USER);
        // 1 open with ADMIN as last author (waiting on user)
        saveTicket(submitter, "Open admin-last", SupportTicketStatus.OPEN,
                SupportTicketCategory.ACCOUNT, null, SupportTicketAuthorRole.ADMIN);
        // 1 resolved
        saveTicket(submitter, "Resolved", SupportTicketStatus.RESOLVED,
                SupportTicketCategory.ACCOUNT, null, SupportTicketAuthorRole.ADMIN);
        em.flush();

        mockMvc.perform(get("/api/v1/admin/support-tickets/queue-stats")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openNeedingAdminReply").value(2))
                .andExpect(jsonPath("$.openTotal").value(3));
    }

    /* ============================================================ */
    /* GET /api/v1/admin/support-tickets/{publicId}                 */
    /* ============================================================ */

    @Test
    void detail_returnsFullTicketIncludingInternalNotes() throws Exception {
        SupportTicket t = saveTicket(submitter, "Detail subject", SupportTicketStatus.OPEN,
                SupportTicketCategory.ACCOUNT, admin.getId(), SupportTicketAuthorRole.USER);
        saveMessage(t, submitter, SupportTicketAuthorRole.USER, "user body", true);
        saveMessage(t, admin, SupportTicketAuthorRole.ADMIN, "admin private note", false);
        saveMessage(t, admin, SupportTicketAuthorRole.ADMIN, "admin public reply", true);
        em.flush();
        em.clear();

        mockMvc.perform(get("/api/v1/admin/support-tickets/" + t.getPublicId())
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(t.getPublicId().toString()))
                // Admin DTO must include the internal note - 3 messages total.
                .andExpect(jsonPath("$.messages.length()").value(3))
                .andExpect(jsonPath("$.messages[?(@.visibleToUser == false)].body")
                        .value(org.hamcrest.Matchers.hasItem("admin private note")))
                .andExpect(jsonPath("$.assignedAdminPublicId").value(admin.getPublicId().toString()));
    }

    /* ============================================================ */
    /* POST /api/v1/admin/support-tickets/{publicId}/messages       */
    /* ============================================================ */

    @Test
    void reply_visible_appendsAndFiresAdminRepliedNotification() throws Exception {
        SupportTicket t = saveTicket(submitter, "Reply target", SupportTicketStatus.OPEN,
                SupportTicketCategory.ACCOUNT, null, SupportTicketAuthorRole.USER);
        OffsetDateTime initialLastMessageAt = t.getLastMessageAt();
        em.flush();

        AdminReplyRequest req = new AdminReplyRequest("admin visible reply", null, false);

        mockMvc.perform(post("/api/v1/admin/support-tickets/" + t.getPublicId() + "/messages")
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("admin visible reply"))
                .andExpect(jsonPath("$.visibleToUser").value(true))
                .andExpect(jsonPath("$.authorRole").value("ADMIN"));

        verify(notifications).supportTicketAdminReplied(
                eq(submitter.getId()), eq(t.getPublicId()), eq("Reply target"),
                anyString());

        SupportTicket reloaded = ticketRepo.findByPublicId(t.getPublicId()).orElseThrow();
        assertThat(reloaded.getLastMessageAuthor()).isEqualTo(SupportTicketAuthorRole.ADMIN);
        assertThat(reloaded.getLastMessageAt()).isAfter(initialLastMessageAt);
    }

    @Test
    void reply_internalNote_appendsInvisibleAndDoesNotNotify() throws Exception {
        SupportTicket t = saveTicket(submitter, "Internal note target", SupportTicketStatus.OPEN,
                SupportTicketCategory.ACCOUNT, null, SupportTicketAuthorRole.USER);
        OffsetDateTime priorLastMessageAt = t.getLastMessageAt();
        SupportTicketAuthorRole priorLastAuthor = t.getLastMessageAuthor();
        em.flush();

        AdminReplyRequest req = new AdminReplyRequest("private internal note", null, true);

        mockMvc.perform(post("/api/v1/admin/support-tickets/" + t.getPublicId() + "/messages")
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("private internal note"))
                .andExpect(jsonPath("$.visibleToUser").value(false));

        // Internal notes never trigger the admin-replied dispatch.
        verify(notifications, never()).supportTicketAdminReplied(
                anyLong(), any(UUID.class), anyString(), anyString());

        // lastMessageAt / lastMessageAuthor are unchanged for invisible writes.
        SupportTicket reloaded = ticketRepo.findByPublicId(t.getPublicId()).orElseThrow();
        assertThat(reloaded.getLastMessageAt()).isEqualTo(priorLastMessageAt);
        assertThat(reloaded.getLastMessageAuthor()).isEqualTo(priorLastAuthor);
    }

    /* ============================================================ */
    /* POST /api/v1/admin/support-tickets/{publicId}/resolve        */
    /* ============================================================ */

    @Test
    void resolve_open_transitionsToResolvedAndFiresNotification() throws Exception {
        SupportTicket t = saveTicket(submitter, "Resolve me", SupportTicketStatus.OPEN,
                SupportTicketCategory.ACCOUNT, admin.getId(), SupportTicketAuthorRole.USER);
        saveMessage(t, submitter, SupportTicketAuthorRole.USER, "please help", true);
        em.flush();

        mockMvc.perform(post("/api/v1/admin/support-tickets/" + t.getPublicId() + "/resolve")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedAt").exists())
                // The system message is present in the messages graph after flush+clear+refetch.
                .andExpect(jsonPath("$.messages[?(@.body == 'Marked resolved by admin')]").exists());

        verify(notifications).supportTicketResolved(
                eq(submitter.getId()), eq(t.getPublicId()), eq("Resolve me"));
    }

    @Test
    void resolve_alreadyResolved_idempotentNoSecondNotification() throws Exception {
        SupportTicket t = saveTicket(submitter, "Already resolved", SupportTicketStatus.RESOLVED,
                SupportTicketCategory.ACCOUNT, admin.getId(), SupportTicketAuthorRole.ADMIN);
        t.setResolvedAt(OffsetDateTime.now().minusHours(1));
        ticketRepo.save(t);
        em.flush();

        mockMvc.perform(post("/api/v1/admin/support-tickets/" + t.getPublicId() + "/resolve")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));

        verify(notifications, never()).supportTicketResolved(
                anyLong(), any(UUID.class), anyString());
    }

    /* ============================================================ */
    /* POST /api/v1/admin/support-tickets/{publicId}/reopen         */
    /* ============================================================ */

    @Test
    void reopen_resolvedTicket_transitionsBackWithSystemMessage() throws Exception {
        SupportTicket t = saveTicket(submitter, "Reopen me", SupportTicketStatus.RESOLVED,
                SupportTicketCategory.ACCOUNT, admin.getId(), SupportTicketAuthorRole.ADMIN);
        t.setResolvedAt(OffsetDateTime.now().minusHours(1));
        ticketRepo.save(t);
        em.flush();

        mockMvc.perform(post("/api/v1/admin/support-tickets/" + t.getPublicId() + "/reopen")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.resolvedAt").doesNotExist())
                .andExpect(jsonPath("$.messages[?(@.body == 'Reopened by admin')]").exists());

        // Reopen is intentionally silent - no notification fires.
        verify(notifications, never()).supportTicketAdminReplied(
                anyLong(), any(UUID.class), anyString(), anyString());
        verify(notifications, never()).supportTicketResolved(
                anyLong(), any(UUID.class), anyString());
    }

    @Test
    void reopen_alreadyOpen_idempotent() throws Exception {
        SupportTicket t = saveTicket(submitter, "Already open", SupportTicketStatus.OPEN,
                SupportTicketCategory.ACCOUNT, null, SupportTicketAuthorRole.USER);
        em.flush();

        mockMvc.perform(post("/api/v1/admin/support-tickets/" + t.getPublicId() + "/reopen")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    /* ============================================================ */
    /* POST /api/v1/admin/support-tickets/{publicId}/assign         */
    /* ============================================================ */

    @Test
    void assign_setsAssignedAdminId() throws Exception {
        SupportTicket t = saveTicket(submitter, "Assign me", SupportTicketStatus.OPEN,
                SupportTicketCategory.ACCOUNT, null, SupportTicketAuthorRole.USER);
        em.flush();

        AssignTicketRequest req = new AssignTicketRequest(admin.getPublicId());

        mockMvc.perform(post("/api/v1/admin/support-tickets/" + t.getPublicId() + "/assign")
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedAdminPublicId").value(admin.getPublicId().toString()));
    }

    @Test
    void assign_nullPublicId_clearsAssignment() throws Exception {
        SupportTicket t = saveTicket(submitter, "Unassign me", SupportTicketStatus.OPEN,
                SupportTicketCategory.ACCOUNT, admin.getId(), SupportTicketAuthorRole.USER);
        em.flush();

        AssignTicketRequest req = new AssignTicketRequest(null);

        mockMvc.perform(post("/api/v1/admin/support-tickets/" + t.getPublicId() + "/assign")
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedAdminPublicId").doesNotExist());
    }

    @Test
    void assign_nonAdminTarget_returns404UnknownTicket() throws Exception {
        SupportTicket t = saveTicket(submitter, "Bad assignment", SupportTicketStatus.OPEN,
                SupportTicketCategory.ACCOUNT, null, SupportTicketAuthorRole.USER);
        em.flush();

        AssignTicketRequest req = new AssignTicketRequest(regularUser.getPublicId());

        mockMvc.perform(post("/api/v1/admin/support-tickets/" + t.getPublicId() + "/assign")
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNKNOWN_TICKET"));
    }

    /* ============================================================ */
    /* PATCH /api/v1/admin/support-tickets/{publicId}               */
    /* ============================================================ */

    @Test
    void patch_updatesCategory() throws Exception {
        SupportTicket t = saveTicket(submitter, "Recategorize", SupportTicketStatus.OPEN,
                SupportTicketCategory.OTHER, null, SupportTicketAuthorRole.USER);
        em.flush();

        PatchTicketRequest req = new PatchTicketRequest(SupportTicketCategory.BIDDING);

        mockMvc.perform(patch("/api/v1/admin/support-tickets/" + t.getPublicId())
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("BIDDING"));
    }

    /* ============================================================ */
    /* Role gating - non-admin tokens get 403                       */
    /* ============================================================ */

    @Test
    void nonAdmin_getQueue_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/support-tickets")
                        .header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isForbidden());
    }

    @Test
    void nonAdmin_getDetail_returns403() throws Exception {
        SupportTicket t = saveTicket(submitter, "Some ticket", SupportTicketStatus.OPEN,
                SupportTicketCategory.ACCOUNT, null, SupportTicketAuthorRole.USER);
        em.flush();

        mockMvc.perform(get("/api/v1/admin/support-tickets/" + t.getPublicId())
                        .header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isForbidden());
    }

    @Test
    void nonAdmin_postReply_returns403() throws Exception {
        SupportTicket t = saveTicket(submitter, "Some ticket", SupportTicketStatus.OPEN,
                SupportTicketCategory.ACCOUNT, null, SupportTicketAuthorRole.USER);
        em.flush();

        AdminReplyRequest req = new AdminReplyRequest("attempted reply", null, false);

        mockMvc.perform(post("/api/v1/admin/support-tickets/" + t.getPublicId() + "/messages")
                        .header("Authorization", "Bearer " + userJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    /* ============================================================ */
    /* Helpers                                                      */
    /* ============================================================ */

    private SupportTicket saveTicket(User owner, String subject,
                                     SupportTicketStatus status,
                                     SupportTicketCategory category,
                                     Long assignedAdminId,
                                     SupportTicketAuthorRole lastAuthor) {
        OffsetDateTime now = OffsetDateTime.now();
        SupportTicket t = SupportTicket.builder()
                .user(owner)
                .subject(subject)
                .category(category)
                .status(status)
                .lastMessageAt(now)
                .lastMessageAuthor(lastAuthor)
                .assignedAdminId(assignedAdminId)
                .build();
        if (status == SupportTicketStatus.RESOLVED) {
            t.setResolvedAt(now);
        }
        return ticketRepo.save(t);
    }

    private SupportTicketMessage saveMessage(SupportTicket ticket, User author,
                                             SupportTicketAuthorRole role,
                                             String body, boolean visibleToUser) {
        SupportTicketMessage m = SupportTicketMessage.builder()
                .ticket(ticket)
                .authorUser(author)
                .authorRole(role)
                .body(body)
                .visibleToUser(visibleToUser)
                .build();
        return messageRepo.save(m);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
