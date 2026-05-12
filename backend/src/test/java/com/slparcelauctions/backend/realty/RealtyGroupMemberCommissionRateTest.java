package com.slparcelauctions.backend.realty;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Repository test for {@link RealtyGroupMember#getAgentCommissionRate()} and
 * {@link RealtyGroupMemberRepository#findCommissionRate(Long, Long)}.
 *
 * <p>Mirrors the {@code @SpringBootTest + @ActiveProfiles("dev")} pattern used by
 * {@code RealtyGroupSlGroupRepositoryTest} and {@code RealtyGroupFkIntegrationTest}.
 *
 * <p>Spec: §3.2, §9.1, plan Task 4.
 *
 * <p>Test cleanup uses the {@code rgmcr-%@test.local} email pattern to scope test-row
 * deletion to this class.
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
class RealtyGroupMemberCommissionRateTest {

    @Autowired UserRepository userRepository;
    @Autowired RealtyGroupRepository realtyGroupRepository;
    @Autowired RealtyGroupMemberRepository memberRepository;
    @Autowired DataSource dataSource;

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var stmt = conn.createStatement()) {
                stmt.execute("""
                    DELETE FROM realty_group_members
                     WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'rgmcr-%@test.local')
                    """);
                stmt.execute("""
                    DELETE FROM realty_groups
                     WHERE leader_id IN (SELECT id FROM users WHERE email LIKE 'rgmcr-%@test.local')
                    """);
                stmt.execute("DELETE FROM users WHERE email LIKE 'rgmcr-%@test.local'");
            }
        }
    }

    @Test
    void agentCommissionRateDefaultsToZero() {
        Fixture fx = persistFixture();

        RealtyGroupMember member = memberRepository.save(RealtyGroupMember.builder()
            .groupId(fx.group.getId())
            .userId(fx.user.getId())
            .joinedAt(OffsetDateTime.now())
            .build());

        RealtyGroupMember reloaded = memberRepository.findById(member.getId()).orElseThrow();
        assertThat(reloaded.getAgentCommissionRate())
            .isNotNull()
            .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void agentCommissionRateRoundtrips() {
        Fixture fx = persistFixture();

        RealtyGroupMember member = memberRepository.save(RealtyGroupMember.builder()
            .groupId(fx.group.getId())
            .userId(fx.user.getId())
            .joinedAt(OffsetDateTime.now())
            .agentCommissionRate(new BigDecimal("0.1234"))
            .build());

        RealtyGroupMember reloaded = memberRepository.findById(member.getId()).orElseThrow();
        assertThat(reloaded.getAgentCommissionRate())
            .isEqualByComparingTo(new BigDecimal("0.1234"));
    }

    @Test
    void findCommissionRate_returnsRate() {
        Fixture fx = persistFixture();

        memberRepository.save(RealtyGroupMember.builder()
            .groupId(fx.group.getId())
            .userId(fx.user.getId())
            .joinedAt(OffsetDateTime.now())
            .agentCommissionRate(new BigDecimal("0.0750"))
            .build());

        Optional<BigDecimal> rate = memberRepository.findCommissionRate(
            fx.group.getId(), fx.user.getId());

        assertThat(rate).isPresent();
        assertThat(rate.get()).isEqualByComparingTo(new BigDecimal("0.0750"));
    }

    @Test
    void findCommissionRate_unknownMember_returnsEmpty() {
        Fixture fx = persistFixture();
        // intentionally no member row persisted

        Optional<BigDecimal> rate = memberRepository.findCommissionRate(
            fx.group.getId(), fx.user.getId());

        assertThat(rate).isEmpty();
    }

    // ─────────────────────── helpers ───────────────────────

    private record Fixture(User user, RealtyGroup group) {}

    private Fixture persistFixture() {
        User leader = userRepository.save(User.builder()
            .username("rgmcr-" + suffix())
            .email("rgmcr-" + UUID.randomUUID() + "@test.local")
            .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
            .displayName("leader")
            .verified(true)
            .slAvatarUuid(UUID.randomUUID())
            .build());

        User agent = userRepository.save(User.builder()
            .username("rgmcr-" + suffix())
            .email("rgmcr-" + UUID.randomUUID() + "@test.local")
            .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
            .displayName("agent")
            .verified(true)
            .slAvatarUuid(UUID.randomUUID())
            .build());

        RealtyGroup group = realtyGroupRepository.save(RealtyGroup.builder()
            .name("RG MCR Test " + suffix())
            .slug("rgmcr-" + suffix())
            .leaderId(leader.getId())
            .build());

        return new Fixture(agent, group);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
