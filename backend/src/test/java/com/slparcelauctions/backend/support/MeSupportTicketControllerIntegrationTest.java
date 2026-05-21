package com.slparcelauctions.backend.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.support.dto.CreateSupportTicketRequest;
import com.slparcelauctions.backend.support.dto.ReplySupportTicketRequest;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Integration coverage for {@link MeSupportTicketController}. Spins the
 * full Spring context (auth chain + slice advice + JPA) and exercises
 * every endpoint via {@link MockMvc} with a real user JWT.
 *
 * <p>{@link ObjectStorageService} is mocked so the attachment-promotion
 * path inside {@link SupportTicketService#createTicket} (and
 * {@link SupportTicketService#userReply}) does not actually hit S3 -
 * the tests here never exercise attachment promotion, so the bean only
 * needs to exist.
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
class MeSupportTicketControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepo;
    @Autowired SupportTicketRepository ticketRepo;
    @Autowired SupportTicketMessageRepository messageRepo;
    @PersistenceContext EntityManager em;

    @MockitoBean ObjectStorageService storage;

    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private User user;
    private User otherUser;
    private User admin;
    private String userJwt;
    private String otherJwt;

    @BeforeEach
    void seed() {
        user = userRepo.save(User.builder()
                .username("user-" + shortId())
                .email("user-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                .displayName("User").role(Role.USER).verified(true).build());
        otherUser = userRepo.save(User.builder()
                .username("other-" + shortId())
                .email("other-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                .displayName("Other").role(Role.USER).verified(true).build());
        admin = userRepo.save(User.builder()
                .username("admin-" + shortId())
                .email("admin-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                .displayName("Admin").role(Role.ADMIN).verified(true).build());
        userJwt = jwtService.issueAccessToken(new AuthPrincipal(
                user.getId(), user.getPublicId(), user.getEmail(), 0L, Role.USER));
        otherJwt = jwtService.issueAccessToken(new AuthPrincipal(
                otherUser.getId(), otherUser.getPublicId(), otherUser.getEmail(),
                0L, Role.USER));
    }

    /* ============================================================ */
    /* GET /api/v1/me/support-tickets - list + filters              */
    /* ============================================================ */

    @Test
    void list_noFilter_returnsAllOwnTickets() throws Exception {
        saveTicket(user, "First subject", SupportTicketStatus.OPEN, SupportTicketCategory.ACCOUNT);
        saveTicket(user, "Second subject", SupportTicketStatus.RESOLVED, SupportTicketCategory.BIDDING);
        // Ticket owned by someone else - must not appear in the response.
        saveTicket(otherUser, "Other user's ticket", SupportTicketStatus.OPEN, SupportTicketCategory.OTHER);
        em.flush();

        mockMvc.perform(get("/api/v1/me/support-tickets")
                        .header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void list_statusOpen_narrows() throws Exception {
        SupportTicket open = saveTicket(user, "Open ticket", SupportTicketStatus.OPEN,
                SupportTicketCategory.ACCOUNT);
        saveTicket(user, "Resolved ticket", SupportTicketStatus.RESOLVED,
                SupportTicketCategory.BIDDING);
        em.flush();

        mockMvc.perform(get("/api/v1/me/support-tickets")
                        .param("status", "OPEN")
                        .header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].publicId").value(open.getPublicId().toString()))
                .andExpect(jsonPath("$.content[0].status").value("OPEN"));
    }

    @Test
    void list_qFilter_narrowsBySubjectSubstring() throws Exception {
        String unique = "uniquesubj" + shortId();
        SupportTicket match = saveTicket(user, "Match contains " + unique + " inside",
                SupportTicketStatus.OPEN, SupportTicketCategory.ACCOUNT);
        saveTicket(user, "No match here", SupportTicketStatus.OPEN,
                SupportTicketCategory.BIDDING);
        em.flush();

        mockMvc.perform(get("/api/v1/me/support-tickets")
                        .param("q", unique)
                        .header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].publicId").value(match.getPublicId().toString()));
    }

    /* ============================================================ */
    /* GET /api/v1/me/support-tickets/{publicId}                    */
    /* ============================================================ */

    @Test
    void detail_returnsTicket_andFiltersOutInternalNotes() throws Exception {
        SupportTicket t = saveTicket(user, "Detail subject", SupportTicketStatus.OPEN,
                SupportTicketCategory.ACCOUNT);
        // Visible user message + invisible admin internal note.
        saveMessage(t, user, SupportTicketAuthorRole.USER, "Hello support", true);
        saveMessage(t, admin, SupportTicketAuthorRole.ADMIN, "private admin note", false);
        em.flush();
        em.clear();

        mockMvc.perform(get("/api/v1/me/support-tickets/" + t.getPublicId())
                        .header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(t.getPublicId().toString()))
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].body").value("Hello support"))
                .andExpect(jsonPath("$.messages[0].visibleToUser").value(true));
    }

    @Test
    void detail_notOwner_returns404_unknownTicket() throws Exception {
        SupportTicket t = saveTicket(otherUser, "Other's ticket", SupportTicketStatus.OPEN,
                SupportTicketCategory.ACCOUNT);
        em.flush();

        mockMvc.perform(get("/api/v1/me/support-tickets/" + t.getPublicId())
                        .header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNKNOWN_TICKET"));
    }

    /* ============================================================ */
    /* POST /api/v1/me/support-tickets                              */
    /* ============================================================ */

    @Test
    void create_happyPath_returnsDtoWithInitialMessage() throws Exception {
        CreateSupportTicketRequest req = new CreateSupportTicketRequest(
                "I need help with bidding",
                SupportTicketCategory.BIDDING,
                "Cannot place a bid on auction X",
                null);

        mockMvc.perform(post("/api/v1/me/support-tickets")
                        .header("Authorization", "Bearer " + userJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").exists())
                .andExpect(jsonPath("$.subject").value("I need help with bidding"))
                .andExpect(jsonPath("$.category").value("BIDDING"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].body").value("Cannot place a bid on auction X"))
                .andExpect(jsonPath("$.messages[0].authorRole").value("USER"));
    }

    @Test
    void create_sixthWithinAnHour_returns429_rateLimited() throws Exception {
        // Seed 5 prior tickets in the trailing-hour window. The rate
        // limiter caps at 5 per hour; the 6th must trip RATE_LIMITED.
        for (int i = 0; i < 5; i++) {
            saveTicket(user, "Prior " + i, SupportTicketStatus.OPEN,
                    SupportTicketCategory.ACCOUNT);
        }
        em.flush();

        CreateSupportTicketRequest req = new CreateSupportTicketRequest(
                "Number six",
                SupportTicketCategory.ACCOUNT,
                "Should be rate-limited",
                null);

        mockMvc.perform(post("/api/v1/me/support-tickets")
                        .header("Authorization", "Bearer " + userJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    /* ============================================================ */
    /* POST /api/v1/me/support-tickets/{publicId}/messages          */
    /* ============================================================ */

    @Test
    void reply_emptyBody_returns400_beanValidation() throws Exception {
        SupportTicket t = saveTicket(user, "Reply target", SupportTicketStatus.OPEN,
                SupportTicketCategory.ACCOUNT);
        em.flush();

        ReplySupportTicketRequest req = new ReplySupportTicketRequest("", null);

        mockMvc.perform(post("/api/v1/me/support-tickets/" + t.getPublicId() + "/messages")
                        .header("Authorization", "Bearer " + userJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reply_onResolvedTicket_autoReopens() throws Exception {
        SupportTicket t = saveTicket(user, "Already resolved", SupportTicketStatus.RESOLVED,
                SupportTicketCategory.ACCOUNT);
        t.setResolvedAt(OffsetDateTime.now().minusHours(1));
        ticketRepo.save(t);
        em.flush();

        ReplySupportTicketRequest req = new ReplySupportTicketRequest(
                "Actually I have a follow-up question", null);

        mockMvc.perform(post("/api/v1/me/support-tickets/" + t.getPublicId() + "/messages")
                        .header("Authorization", "Bearer " + userJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        // GET the ticket back and confirm the status flipped to OPEN.
        mockMvc.perform(get("/api/v1/me/support-tickets/" + t.getPublicId())
                        .header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));

        SupportTicket reloaded = ticketRepo.findByPublicId(t.getPublicId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(SupportTicketStatus.OPEN);
    }

    @Test
    void reply_notOwner_returns404_unknownTicket() throws Exception {
        SupportTicket t = saveTicket(user, "User's ticket", SupportTicketStatus.OPEN,
                SupportTicketCategory.ACCOUNT);
        em.flush();

        ReplySupportTicketRequest req = new ReplySupportTicketRequest(
                "I am not the owner", null);

        mockMvc.perform(post("/api/v1/me/support-tickets/" + t.getPublicId() + "/messages")
                        .header("Authorization", "Bearer " + otherJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNKNOWN_TICKET"));
    }

    /* ============================================================ */
    /* Helpers                                                      */
    /* ============================================================ */

    private SupportTicket saveTicket(User owner, String subject,
                                     SupportTicketStatus status,
                                     SupportTicketCategory category) {
        OffsetDateTime now = OffsetDateTime.now();
        SupportTicket t = SupportTicket.builder()
                .user(owner)
                .subject(subject)
                .category(category)
                .status(status)
                .lastMessageAt(now)
                .lastMessageAuthor(SupportTicketAuthorRole.USER)
                .build();
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
