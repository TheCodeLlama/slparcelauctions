package com.slparcelauctions.backend.realty.wallet.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Sub-project G section 7.2 -- end-to-end MockMvc coverage for
 * {@link AdminRealtyGroupWalletController}. Uses a full
 * {@code @SpringBootTest} context so the real JWT auth chain runs and
 * {@code @PreAuthorize("hasRole('ADMIN')")} (plus the security-chain
 * {@code /api/v1/admin/**} gate) is exercised authentically rather than
 * stubbed.
 *
 * <p>Covers: credit happy path, debit happy path, blank-reason validation
 * (400 via Bean Validation), oversize-reason validation (400 via
 * Bean Validation), zero-amount service guard (400 via
 * {@code IllegalArgumentException}), out-of-range ceiling (422 via
 * {@code AdminAdjustAmountOutOfRangeException}), insufficient balance
 * (422 via {@code InsufficientGroupBalanceException}), non-admin caller
 * (403), and unknown group (404).
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
class AdminRealtyGroupWalletControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;
    @Autowired RealtyGroupRepository groupRepository;

    // ─────────────────────────── happy paths ───────────────────────────

    @Test
    void post_walletAdjust_returns200WithUpdatedWalletDto_whenCrediting() throws Exception {
        User admin = seedAdmin("credit");
        User leader = seedUser("leader-credit");
        RealtyGroup g = saveGroup("ctrl-credit", leader.getId(), 0L);

        mvc.perform(post("/api/v1/admin/realty-groups/{publicId}/wallet/adjust", g.getPublicId())
                .header("Authorization", "Bearer " + adminToken(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount": 1500, "reason": "Manual credit for support ticket SLPA-42"}
                        """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").value(1500))
            .andExpect(jsonPath("$.available").value(1500))
            .andExpect(jsonPath("$.recentLedger[0].entryType").value("ADMIN_ADJUSTMENT"))
            .andExpect(jsonPath("$.recentLedger[0].amount").value(1500));
    }

    @Test
    void post_walletAdjust_returns200WithUpdatedWalletDto_whenDebiting() throws Exception {
        User admin = seedAdmin("debit");
        User leader = seedUser("leader-debit");
        RealtyGroup g = saveGroup("ctrl-debit", leader.getId(), 5_000L);

        mvc.perform(post("/api/v1/admin/realty-groups/{publicId}/wallet/adjust", g.getPublicId())
                .header("Authorization", "Bearer " + adminToken(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount": -1200, "reason": "Recover bad payout"}
                        """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").value(3800))
            .andExpect(jsonPath("$.recentLedger[0].entryType").value("ADMIN_ADJUSTMENT"))
            .andExpect(jsonPath("$.recentLedger[0].amount").value(-1200));
    }

    // ─────────────────────────── validation: 400 paths ───────────────────────────

    @Test
    void post_walletAdjust_returns400_whenAmountIsZero() throws Exception {
        User admin = seedAdmin("zero");
        User leader = seedUser("leader-zero");
        RealtyGroup g = saveGroup("ctrl-zero", leader.getId(), 0L);

        mvc.perform(post("/api/v1/admin/realty-groups/{publicId}/wallet/adjust", g.getPublicId())
                .header("Authorization", "Bearer " + adminToken(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount": 0, "reason": "nope"}
                        """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void post_walletAdjust_returns400_whenReasonIsBlank() throws Exception {
        User admin = seedAdmin("blank");
        User leader = seedUser("leader-blank");
        RealtyGroup g = saveGroup("ctrl-blank", leader.getId(), 0L);

        mvc.perform(post("/api/v1/admin/realty-groups/{publicId}/wallet/adjust", g.getPublicId())
                .header("Authorization", "Bearer " + adminToken(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount": 100, "reason": "   "}
                        """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void post_walletAdjust_returns400_whenReasonExceeds500Chars() throws Exception {
        User admin = seedAdmin("long");
        User leader = seedUser("leader-long");
        RealtyGroup g = saveGroup("ctrl-long", leader.getId(), 0L);

        String tooLong = "x".repeat(501);
        String body = "{\"amount\": 100, \"reason\": \"" + tooLong + "\"}";

        mvc.perform(post("/api/v1/admin/realty-groups/{publicId}/wallet/adjust", g.getPublicId())
                .header("Authorization", "Bearer " + adminToken(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    // ─────────────────────────── 422 paths ───────────────────────────

    @Test
    void post_walletAdjust_returns422WithCode_whenAmountExceedsCeiling() throws Exception {
        User admin = seedAdmin("huge");
        User leader = seedUser("leader-huge");
        RealtyGroup g = saveGroup("ctrl-huge", leader.getId(), 0L);

        // Ceiling default is 10,000,000 L$ (AdminWalletAdjustProperties).
        mvc.perform(post("/api/v1/admin/realty-groups/{publicId}/wallet/adjust", g.getPublicId())
                .header("Authorization", "Bearer " + adminToken(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount": 20000000, "reason": "Too big to fit"}
                        """))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("ADMIN_ADJUST_AMOUNT_OUT_OF_RANGE"))
            .andExpect(jsonPath("$.amount").value(20000000))
            .andExpect(jsonPath("$.ceiling").value(10000000));
    }

    @Test
    void post_walletAdjust_returns422_whenDebitWouldOverdraw() throws Exception {
        User admin = seedAdmin("ovrd");
        User leader = seedUser("leader-ovrd");
        RealtyGroup g = saveGroup("ctrl-ovrd", leader.getId(), 500L);

        mvc.perform(post("/api/v1/admin/realty-groups/{publicId}/wallet/adjust", g.getPublicId())
                .header("Authorization", "Bearer " + adminToken(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount": -1000, "reason": "Recover overpaid"}
                        """))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_GROUP_BALANCE"));
    }

    // ─────────────────────────── 403 / 404 paths ───────────────────────────

    @Test
    void post_walletAdjust_returns403_whenCallerIsNotAdmin() throws Exception {
        User caller = seedUser("nonadmin");
        User leader = seedUser("leader-forbid");
        RealtyGroup g = saveGroup("ctrl-forbid", leader.getId(), 0L);

        mvc.perform(post("/api/v1/admin/realty-groups/{publicId}/wallet/adjust", g.getPublicId())
                .header("Authorization", "Bearer " + userToken(caller))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount": 100, "reason": "drive-by"}
                        """))
            .andExpect(status().isForbidden());
    }

    @Test
    void post_walletAdjust_returns404_whenGroupUnknown() throws Exception {
        User admin = seedAdmin("notfound");
        UUID unknown = UUID.randomUUID();

        mvc.perform(post("/api/v1/admin/realty-groups/{publicId}/wallet/adjust", unknown)
                .header("Authorization", "Bearer " + adminToken(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount": 100, "reason": "Looking for a ghost"}
                        """))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("REALTY_GROUP_NOT_FOUND"));
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private User seedAdmin(String suffix) {
        return userRepository.save(User.builder()
            .username("ctrl-admin-" + suffix + "-" + uniq())
            .email("ctrl-admin-" + suffix + "-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x")
            .role(Role.ADMIN)
            .build());
    }

    private User seedUser(String label) {
        return userRepository.save(User.builder()
            .username("u-" + label + "-" + uniq())
            .email("u-" + label + "-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x")
            .build());
    }

    private RealtyGroup saveGroup(String prefix, Long leaderId, long balance) {
        String slug = prefix + "-" + uniq();
        return groupRepository.save(RealtyGroup.builder()
            .name(prefix + " " + slug)
            .slug(slug)
            .leaderId(leaderId)
            .balanceLindens(balance)
            .build());
    }

    private String adminToken(User admin) {
        return jwtService.issueAccessToken(new AuthPrincipal(
            admin.getId(), admin.getPublicId(), admin.getUsername(), 0L, Role.ADMIN));
    }

    private String userToken(User u) {
        return jwtService.issueAccessToken(new AuthPrincipal(
            u.getId(), u.getPublicId(), u.getUsername(), 0L, Role.USER));
    }

    private static String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
