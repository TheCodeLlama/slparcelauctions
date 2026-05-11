package com.slparcelauctions.backend.realty;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.persistence.OptimisticLockException;

/**
 * Concurrency integration test for the realty groups slice — exercises the optimistic-lock
 * collision on concurrent permission updates against a real Postgres row.
 *
 * <p>Pattern mirrors {@code BaseMutableEntityVersionTest}: explicit {@link TransactionTemplate}
 * blocks so each "thread" has its own committed transaction, then a stale-detached copy is
 * flushed against the now-incremented row's {@code @Version} column. The flush raises
 * {@link OptimisticLockException} (or Spring's {@link ObjectOptimisticLockingFailureException}
 * wrapper) — the spec's "one wins, the other loses" guarantee.
 *
 * <p>Not transactional at the test-method level — an ambient tx would serialize both flushes
 * into a single session and mask the conflict. Cleanup is via {@link #cleanupRealtyChain}
 * (same email-pattern scoping as {@code RealtyGroupIntegrationTest}).
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
class RealtyGroupConcurrencyIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired RealtyGroupRepository groupRepository;
    @Autowired RealtyGroupMemberRepository memberRepository;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    @AfterEach
    void cleanupRealtyChain() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var stmt = conn.createStatement()) {
                stmt.execute("""
                    DELETE FROM notification
                     WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'rgconc-%@test.local')
                    """);
                stmt.execute("""
                    DELETE FROM sl_im_message
                     WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'rgconc-%@test.local')
                    """);
                stmt.execute("""
                    DELETE FROM realty_group_invitations
                     WHERE invited_user_id IN (SELECT id FROM users WHERE email LIKE 'rgconc-%@test.local')
                        OR invited_by_id IN (SELECT id FROM users WHERE email LIKE 'rgconc-%@test.local')
                    """);
                stmt.execute("""
                    DELETE FROM realty_group_members
                     WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'rgconc-%@test.local')
                    """);
                stmt.execute("""
                    DELETE FROM realty_groups
                     WHERE leader_id IN (SELECT id FROM users WHERE email LIKE 'rgconc-%@test.local')
                    """);
                stmt.execute("DELETE FROM users WHERE email LIKE 'rgconc-%@test.local'");
            }
        }
    }

    @Test
    void concurrentPermissionUpdates_oneWins_otherRaisesOptimisticLock() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        // Seed: leader + agent member row, version=0.
        Long[] memberIdHolder = new Long[1];
        tx.executeWithoutResult(status -> {
            User leader = userRepository.save(User.builder()
                .username("ldr-" + UUID.randomUUID().toString().substring(0, 8))
                .email("rgconc-leader-" + UUID.randomUUID() + "@test.local")
                .passwordHash("x").displayName("Leader").build());
            User agent = userRepository.save(User.builder()
                .username("agt-" + UUID.randomUUID().toString().substring(0, 8))
                .email("rgconc-agent-" + UUID.randomUUID() + "@test.local")
                .passwordHash("x").displayName("Agent").build());
            String slug = "race-" + UUID.randomUUID().toString().substring(0, 8);
            RealtyGroup g = groupRepository.save(RealtyGroup.builder()
                .name("Race " + slug).slug(slug).leaderId(leader.getId()).build());
            RealtyGroupMember agentRow = RealtyGroupMember.builder()
                .groupId(g.getId()).userId(agent.getId()).joinedAt(OffsetDateTime.now()).build();
            agentRow.setPermissionSet(EnumSet.noneOf(RealtyGroupPermission.class));
            memberIdHolder[0] = memberRepository.save(agentRow).getId();
        });
        Long memberId = memberIdHolder[0];

        // Two threads each load the member row in their own tx, mutate permissions, and
        // flush. The first to commit wins; the second's saveAndFlush trips on the version
        // mismatch.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch loaded = new CountDownLatch(2);
        CountDownLatch goSignal = new CountDownLatch(1);
        try {
            Callable<Throwable> task = () -> {
                try {
                    tx.execute(status -> {
                        RealtyGroupMember row = memberRepository.findById(memberId).orElseThrow();
                        loaded.countDown();
                        try {
                            // Block until both threads have loaded the row at version=0.
                            goSignal.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                        EnumSet<RealtyGroupPermission> newPerms = EnumSet.of(
                            Thread.currentThread().getName().endsWith("-1")
                                ? RealtyGroupPermission.INVITE_AGENTS
                                : RealtyGroupPermission.REMOVE_AGENTS);
                        row.setPermissionSet(newPerms);
                        memberRepository.saveAndFlush(row);
                        return null;
                    });
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            };
            Future<Throwable> f1 = pool.submit(task);
            Future<Throwable> f2 = pool.submit(task);

            loaded.await(5, TimeUnit.SECONDS);
            goSignal.countDown();

            Throwable t1 = f1.get(10, TimeUnit.SECONDS);
            Throwable t2 = f2.get(10, TimeUnit.SECONDS);

            // Exactly one of the two flushes should have raised the optimistic-lock failure.
            // The other should have returned cleanly. The wrappers vary by Hibernate path
            // — either the JPA OptimisticLockException or Spring's
            // ObjectOptimisticLockingFailureException counts.
            int failures = 0;
            if (isOptimisticLockFailure(t1)) failures++;
            if (isOptimisticLockFailure(t2)) failures++;
            int successes = 0;
            if (t1 == null) successes++;
            if (t2 == null) successes++;

            assertThat(failures)
                .as("exactly one of the two concurrent permission updates should fail with OptimisticLock")
                .isEqualTo(1);
            assertThat(successes)
                .as("exactly one of the two concurrent permission updates should succeed")
                .isEqualTo(1);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static boolean isOptimisticLockFailure(Throwable t) {
        if (t == null) return false;
        Throwable cursor = t;
        while (cursor != null) {
            if (cursor instanceof OptimisticLockException
                || cursor instanceof ObjectOptimisticLockingFailureException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

}
