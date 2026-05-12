package com.slparcelauctions.backend.realty.slgroup;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.GroupPageData;

import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class SlGroupAboutTextPollTaskTest {

    @Mock RealtyGroupSlGroupRepository repo;
    @Mock SlWorldApiClient worldApi;

    @Spy
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-05-12T12:00:00Z"), ZoneOffset.UTC);

    @InjectMocks
    SlGroupAboutTextPollTask task;

    private static final OffsetDateTime NOW =
            OffsetDateTime.ofInstant(Instant.parse("2026-05-12T12:00:00Z"), ZoneOffset.UTC);

    private RealtyGroupSlGroup pendingRow(String code) {
        return RealtyGroupSlGroup.builder()
                .realtyGroupId(42L)
                .slGroupUuid(UUID.randomUUID())
                .verified(false)
                .verificationCode(code)
                .pollAttempts(0)
                .build();
    }

    @Test
    void pollOne_matchingAboutText_flipsToVerified() {
        RealtyGroupSlGroup row = pendingRow("SLPA-TESTCODE");
        UUID slGroupUuid = row.getSlGroupUuid();
        GroupPageData page = new GroupPageData(
                slGroupUuid,
                "My Group Name",
                "Welcome to my group! Code: SLPA-TESTCODE thanks.",
                null);

        when(worldApi.fetchGroupPage(slGroupUuid)).thenReturn(Mono.just(page));
        when(repo.save(any(RealtyGroupSlGroup.class))).thenAnswer(inv -> inv.getArgument(0));

        RealtyGroupSlGroup result = task.pollOne(row, NOW);

        assertThat(result.isVerified()).isTrue();
        assertThat(result.getVerifiedAt()).isEqualTo(NOW);
        assertThat(result.getVerifiedVia()).isEqualTo(SlGroupVerifyMethod.ABOUT_TEXT);
        assertThat(result.getVerificationCode()).isNull();
        assertThat(result.getSlGroupName()).isEqualTo("My Group Name");
        verify(repo).save(row);
    }

    @Test
    void pollOne_noMatch_incrementsAttemptAndStampsLastPolledAt() {
        RealtyGroupSlGroup row = pendingRow("SLPA-TESTCODE");
        UUID slGroupUuid = row.getSlGroupUuid();
        GroupPageData page = new GroupPageData(
                slGroupUuid,
                "My Group Name",
                "No code here.",
                null);

        when(worldApi.fetchGroupPage(slGroupUuid)).thenReturn(Mono.just(page));
        when(repo.save(any(RealtyGroupSlGroup.class))).thenAnswer(inv -> inv.getArgument(0));

        RealtyGroupSlGroup result = task.pollOne(row, NOW);

        assertThat(result.isVerified()).isFalse();
        assertThat(result.getVerifiedAt()).isNull();
        assertThat(result.getVerifiedVia()).isNull();
        assertThat(result.getPollAttempts()).isEqualTo(1);
        assertThat(result.getLastPolledAt()).isEqualTo(NOW);
        verify(repo).save(row);
    }

    @Test
    void pollOne_worldApiThrows_incrementsAttempt() {
        RealtyGroupSlGroup row = pendingRow("SLPA-TESTCODE");
        UUID slGroupUuid = row.getSlGroupUuid();

        when(worldApi.fetchGroupPage(slGroupUuid))
                .thenReturn(Mono.error(new RuntimeException("world api boom")));
        when(repo.save(any(RealtyGroupSlGroup.class))).thenAnswer(inv -> inv.getArgument(0));

        RealtyGroupSlGroup result = task.pollOne(row, NOW);

        assertThat(result.isVerified()).isFalse();
        assertThat(result.getPollAttempts()).isEqualTo(1);
        assertThat(result.getLastPolledAt()).isEqualTo(NOW);
        verify(repo).save(row);
    }

    @Test
    void pollOne_nullPageData_incrementsAttempt() {
        RealtyGroupSlGroup row = pendingRow("SLPA-TESTCODE");
        UUID slGroupUuid = row.getSlGroupUuid();

        when(worldApi.fetchGroupPage(slGroupUuid)).thenReturn(Mono.empty());
        when(repo.save(any(RealtyGroupSlGroup.class))).thenAnswer(inv -> inv.getArgument(0));

        RealtyGroupSlGroup result = task.pollOne(row, NOW);

        assertThat(result.isVerified()).isFalse();
        assertThat(result.getPollAttempts()).isEqualTo(1);
        assertThat(result.getLastPolledAt()).isEqualTo(NOW);
        verify(repo).save(row);
    }

    @Test
    void runScheduled_pollsEachDueRow() {
        RealtyGroupSlGroup r1 = pendingRow("SLPA-CODE1");
        RealtyGroupSlGroup r2 = pendingRow("SLPA-CODE2");
        UUID u1 = r1.getSlGroupUuid();
        UUID u2 = r2.getSlGroupUuid();

        when(repo.findDueForAboutTextPoll(any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(List.of(r1, r2));
        when(worldApi.fetchGroupPage(u1)).thenReturn(
                Mono.just(new GroupPageData(u1, "G1", "no match", null)));
        when(worldApi.fetchGroupPage(u2)).thenReturn(
                Mono.just(new GroupPageData(u2, "G2", "no match either", null)));
        when(repo.save(any(RealtyGroupSlGroup.class))).thenAnswer(inv -> inv.getArgument(0));

        task.runScheduled();

        verify(worldApi).fetchGroupPage(u1);
        verify(worldApi).fetchGroupPage(u2);
        ArgumentCaptor<RealtyGroupSlGroup> saved = ArgumentCaptor.forClass(RealtyGroupSlGroup.class);
        verify(repo, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).containsExactlyInAnyOrder(r1, r2);
    }

    @Test
    void runScheduled_pollOneThrows_continuesToNextRow() {
        RealtyGroupSlGroup r1 = pendingRow("SLPA-CODE1");
        RealtyGroupSlGroup r2 = pendingRow("SLPA-CODE2");
        UUID u1 = r1.getSlGroupUuid();
        UUID u2 = r2.getSlGroupUuid();

        when(repo.findDueForAboutTextPoll(any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(List.of(r1, r2));
        // Row 1: world API call throws, then repo.save also throws so pollOne escapes.
        when(worldApi.fetchGroupPage(u1)).thenReturn(
                Mono.error(new RuntimeException("boom from world api")));
        // Row 2: ordinary no-match path.
        when(worldApi.fetchGroupPage(u2)).thenReturn(
                Mono.just(new GroupPageData(u2, "G2", "no match", null)));
        // First save (for r1) throws; the loop catches it and continues to r2.
        when(repo.save(any(RealtyGroupSlGroup.class)))
                .thenThrow(new RuntimeException("save boom"))
                .thenAnswer(inv -> inv.getArgument(0));

        task.runScheduled();

        // Both rows were polled despite r1's failure.
        verify(worldApi).fetchGroupPage(u1);
        verify(worldApi).fetchGroupPage(u2);
        verify(repo, times(2)).save(any(RealtyGroupSlGroup.class));
    }
}
