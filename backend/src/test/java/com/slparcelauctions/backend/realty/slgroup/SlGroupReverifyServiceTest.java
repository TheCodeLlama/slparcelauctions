package com.slparcelauctions.backend.realty.slgroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.moderation.RealtyGroupModerationProperties;
import com.slparcelauctions.backend.realty.moderation.RealtyGroupModerationProperties.SlGroupReverify;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.GroupPageData;
import com.slparcelauctions.backend.sl.exception.ExternalApiTimeoutException;

import reactor.core.publisher.Mono;

/**
 * Unit tests for {@link SlGroupReverifyService}. Mockito-driven; fixed clock for
 * determinism. Sub-project F spec §13.2.
 *
 * <p>Coverage matrix:
 * <ul>
 *   <li>Happy path: founder unchanged, fields stamped, no drift, no notification.</li>
 *   <li>FOUNDER_CHANGED: page parses with a different founder, drift flagged + leader
 *       notified.</li>
 *   <li>GROUP_NOT_FOUND: WebClient 404 from the World API, drift flagged immediately
 *       (no failure-counter ramp), leader notified.</li>
 *   <li>FETCH_FAILED_REPEATEDLY:
 *       <ul>
 *         <li>below threshold → counter increments, no drift, no notification;</li>
 *         <li>at threshold → drift flagged + leader notified.</li>
 *       </ul></li>
 *   <li>Counter reset: a successful fetch resets the failure counter to zero.</li>
 *   <li>last_revalidated_at is intentionally left untouched on fetch failure
 *       (only stamped on success).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SlGroupReverifyServiceTest {

    private static final OffsetDateTime FIXED_NOW =
        OffsetDateTime.of(2026, 5, 12, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime EARLIER =
        OffsetDateTime.of(2026, 4, 1, 8, 0, 0, 0, ZoneOffset.UTC);
    private static final Long SL_GROUP_ID = 7L;
    private static final Long REALTY_GROUP_ID = 42L;
    private static final Long LEADER_USER_ID = 9L;
    private static final UUID SL_GROUP_UUID =
        UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORIGINAL_FOUNDER =
        UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID NEW_FOUNDER =
        UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final int FAILURE_THRESHOLD = 3;

    @Mock RealtyGroupSlGroupRepository slGroupRepo;
    @Mock RealtyGroupRepository realtyGroupRepo;
    @Mock SlWorldApiClient slWorldApiClient;
    @Mock NotificationPublisher notificationPublisher;

    Clock clock;
    RealtyGroupModerationProperties props;
    SlGroupReverifyService service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_NOW.toInstant(), ZoneOffset.UTC);
        props = new RealtyGroupModerationProperties();
        SlGroupReverify slGroupProps = new SlGroupReverify();
        slGroupProps.setReverifyFetchFailureThreshold(FAILURE_THRESHOLD);
        props.setSlGroup(slGroupProps);
        service = new SlGroupReverifyService(
            slGroupRepo, realtyGroupRepo, slWorldApiClient,
            notificationPublisher, props, clock);

        // Most tests need the realty group resolved for leader-targeted notifications.
        // Tests that don't flag drift get this stub lenient-ly so unused stubbing
        // doesn't fail the run.
        RealtyGroup group = RealtyGroup.builder()
            .name("Sunset Realty")
            .leaderId(LEADER_USER_ID)
            .build();
        setId(group, REALTY_GROUP_ID);
        lenient().when(realtyGroupRepo.findById(REALTY_GROUP_ID))
            .thenReturn(Optional.of(group));
    }

    @Test
    void recheck_happyPath_updatesLastRevalidatedAtAndCurrentFounderUuid() {
        RealtyGroupSlGroup row = buildRow();
        row.setLastRevalidatedAt(EARLIER);
        when(slGroupRepo.findById(SL_GROUP_ID)).thenReturn(Optional.of(row));
        when(slWorldApiClient.fetchGroupPage(SL_GROUP_UUID)).thenReturn(Mono.just(
            new GroupPageData(SL_GROUP_UUID, "Sunset Realty", null, ORIGINAL_FOUNDER)));

        SlGroupReverifyResult result = service.recheck(SL_GROUP_ID);

        assertThat(row.getLastRevalidatedAt()).isEqualTo(FIXED_NOW);
        assertThat(row.getCurrentFounderUuid()).isEqualTo(ORIGINAL_FOUNDER);
        assertThat(row.getConsecutiveFetchFailures()).isZero();
        assertThat(row.getDriftDetectedAt()).isNull();
        assertThat(row.getDriftReason()).isNull();
        assertThat(result.driftDetected()).isFalse();
        assertThat(result.driftReason()).isNull();
        assertThat(result.currentFounderUuid()).isEqualTo(ORIGINAL_FOUNDER);
        verifyNoInteractions(notificationPublisher);
    }

    @Test
    void recheck_whenFounderChanged_setsDriftDetectedAndReason() {
        RealtyGroupSlGroup row = buildRow();
        when(slGroupRepo.findById(SL_GROUP_ID)).thenReturn(Optional.of(row));
        when(slWorldApiClient.fetchGroupPage(SL_GROUP_UUID)).thenReturn(Mono.just(
            new GroupPageData(SL_GROUP_UUID, "Sunset Realty", null, NEW_FOUNDER)));

        SlGroupReverifyResult result = service.recheck(SL_GROUP_ID);

        // Successful fetch — counters / stamps still reset.
        assertThat(row.getLastRevalidatedAt()).isEqualTo(FIXED_NOW);
        assertThat(row.getCurrentFounderUuid()).isEqualTo(NEW_FOUNDER);
        assertThat(row.getConsecutiveFetchFailures()).isZero();
        // Drift flagged with the original founder mismatch.
        assertThat(row.getDriftDetectedAt()).isEqualTo(FIXED_NOW);
        assertThat(row.getDriftReason()).isEqualTo(SlGroupDriftReason.FOUNDER_CHANGED.name());
        assertThat(result.driftDetected()).isTrue();
        assertThat(result.driftReason()).isEqualTo(SlGroupDriftReason.FOUNDER_CHANGED.name());
        assertThat(result.currentFounderUuid()).isEqualTo(NEW_FOUNDER);
        verify(notificationPublisher).realtyGroupSlGroupDriftDetected(
            eq(LEADER_USER_ID),
            eq(REALTY_GROUP_ID),
            eq("Sunset Realty SL"),
            eq(SlGroupDriftReason.FOUNDER_CHANGED.name()));
    }

    @Test
    void recheck_when404_setsDriftDetectedWithGroupNotFound() {
        RealtyGroupSlGroup row = buildRow();
        when(slGroupRepo.findById(SL_GROUP_ID)).thenReturn(Optional.of(row));
        when(slWorldApiClient.fetchGroupPage(SL_GROUP_UUID)).thenReturn(Mono.error(
            WebClientResponseException.create(
                HttpStatus.NOT_FOUND.value(),
                "Not Found",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8)));

        SlGroupReverifyResult result = service.recheck(SL_GROUP_ID);

        // 404 skips the failure-counter ramp and flags immediately.
        assertThat(row.getDriftDetectedAt()).isEqualTo(FIXED_NOW);
        assertThat(row.getDriftReason()).isEqualTo(SlGroupDriftReason.GROUP_NOT_FOUND.name());
        assertThat(row.getConsecutiveFetchFailures()).isZero();
        assertThat(result.driftDetected()).isTrue();
        assertThat(result.driftReason()).isEqualTo(SlGroupDriftReason.GROUP_NOT_FOUND.name());
        assertThat(result.currentFounderUuid()).isNull();
        verify(notificationPublisher).realtyGroupSlGroupDriftDetected(
            eq(LEADER_USER_ID),
            eq(REALTY_GROUP_ID),
            anyString(),
            eq(SlGroupDriftReason.GROUP_NOT_FOUND.name()));
    }

    @Test
    void recheck_whenFetchFailsBelowThreshold_doesNotFlagDriftButIncrementsCounter() {
        RealtyGroupSlGroup row = buildRow();
        // Starts at 0 failures; below-threshold increment lands at 1.
        when(slGroupRepo.findById(SL_GROUP_ID)).thenReturn(Optional.of(row));
        when(slWorldApiClient.fetchGroupPage(SL_GROUP_UUID)).thenReturn(Mono.error(
            new ExternalApiTimeoutException("World", "read timeout")));

        SlGroupReverifyResult result = service.recheck(SL_GROUP_ID);

        assertThat(row.getConsecutiveFetchFailures()).isEqualTo(1);
        assertThat(row.getDriftDetectedAt()).isNull();
        assertThat(row.getDriftReason()).isNull();
        assertThat(result.driftDetected()).isFalse();
        assertThat(result.driftReason()).isNull();
        assertThat(result.currentFounderUuid()).isNull();
        verify(notificationPublisher, never()).realtyGroupSlGroupDriftDetected(
            anyLong(), anyLong(), any(), any());
    }

    @Test
    void recheck_whenFetchFailsAtThreshold_flagsDriftWithFetchFailedRepeatedly() {
        RealtyGroupSlGroup row = buildRow();
        // Counter is FAILURE_THRESHOLD - 1 (= 2 for default threshold 3) entering this pass.
        // Next failure increments to FAILURE_THRESHOLD and crosses the bar.
        row.setConsecutiveFetchFailures(FAILURE_THRESHOLD - 1);
        when(slGroupRepo.findById(SL_GROUP_ID)).thenReturn(Optional.of(row));
        when(slWorldApiClient.fetchGroupPage(SL_GROUP_UUID)).thenReturn(Mono.error(
            new ExternalApiTimeoutException("World", "read timeout")));

        SlGroupReverifyResult result = service.recheck(SL_GROUP_ID);

        assertThat(row.getConsecutiveFetchFailures()).isEqualTo(FAILURE_THRESHOLD);
        assertThat(row.getDriftDetectedAt()).isEqualTo(FIXED_NOW);
        assertThat(row.getDriftReason())
            .isEqualTo(SlGroupDriftReason.FETCH_FAILED_REPEATEDLY.name());
        assertThat(result.driftDetected()).isTrue();
        assertThat(result.driftReason())
            .isEqualTo(SlGroupDriftReason.FETCH_FAILED_REPEATEDLY.name());
        assertThat(result.currentFounderUuid()).isNull();
        verify(notificationPublisher).realtyGroupSlGroupDriftDetected(
            eq(LEADER_USER_ID),
            eq(REALTY_GROUP_ID),
            anyString(),
            eq(SlGroupDriftReason.FETCH_FAILED_REPEATEDLY.name()));
    }

    @Test
    void recheck_resetCounterOnSuccessfulFetch() {
        RealtyGroupSlGroup row = buildRow();
        row.setConsecutiveFetchFailures(2);
        when(slGroupRepo.findById(SL_GROUP_ID)).thenReturn(Optional.of(row));
        when(slWorldApiClient.fetchGroupPage(SL_GROUP_UUID)).thenReturn(Mono.just(
            new GroupPageData(SL_GROUP_UUID, "Sunset Realty", null, ORIGINAL_FOUNDER)));

        service.recheck(SL_GROUP_ID);

        assertThat(row.getConsecutiveFetchFailures()).isZero();
        assertThat(row.getLastRevalidatedAt()).isEqualTo(FIXED_NOW);
        assertThat(row.getDriftDetectedAt()).isNull();
    }

    @Test
    void recheck_doesNotUpdateLastRevalidatedAtOnFetchFailure() {
        RealtyGroupSlGroup row = buildRow();
        row.setLastRevalidatedAt(EARLIER);
        when(slGroupRepo.findById(SL_GROUP_ID)).thenReturn(Optional.of(row));
        when(slWorldApiClient.fetchGroupPage(SL_GROUP_UUID)).thenReturn(Mono.error(
            new ExternalApiTimeoutException("World", "read timeout")));

        service.recheck(SL_GROUP_ID);

        // Stays pinned to the prior successful revalidation — never advanced by a
        // failed fetch. The cadence picker in SlGroupReverifyTask relies on this.
        assertThat(row.getLastRevalidatedAt()).isEqualTo(EARLIER);
        assertThat(row.getConsecutiveFetchFailures()).isEqualTo(1);
    }

    // ─────────────────────────── helpers ────────────────────────────────

    private RealtyGroupSlGroup buildRow() {
        RealtyGroupSlGroup row = RealtyGroupSlGroup.builder()
            .realtyGroupId(REALTY_GROUP_ID)
            .slGroupUuid(SL_GROUP_UUID)
            .slGroupName("Sunset Realty SL")
            .verified(true)
            .founderAvatarUuid(ORIGINAL_FOUNDER)
            .build();
        setId(row, SL_GROUP_ID);
        return row;
    }

    /** Reflection helper: set the inherited Long id on a BaseEntity subclass. */
    private static void setId(Object entity, Long id) {
        try {
            java.lang.reflect.Field f = findIdField(entity.getClass());
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static java.lang.reflect.Field findIdField(Class<?> c) throws NoSuchFieldException {
        Class<?> cur = c;
        while (cur != null) {
            try {
                return cur.getDeclaredField("id");
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            }
        }
        throw new NoSuchFieldException("id field not found in " + c);
    }
}
