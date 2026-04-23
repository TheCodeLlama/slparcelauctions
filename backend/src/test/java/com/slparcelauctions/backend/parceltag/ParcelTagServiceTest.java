package com.slparcelauctions.backend.parceltag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.parceltag.dto.ParcelTagGroupResponse;

class ParcelTagServiceTest {

    private ParcelTagRepository repo;
    private ParcelTagService service;

    @BeforeEach
    void setup() {
        repo = mock(ParcelTagRepository.class);
        service = new ParcelTagService(repo);
    }

    @Test
    void seedDefaultTagsIfEmpty_runsInsertsWhenTableEmpty() {
        when(repo.count()).thenReturn(0L);

        service.seedDefaultTagsIfEmpty();

        // 25 inserts — one per canonical tag row.
        verify(repo, times(25)).save(any(ParcelTag.class));
    }

    @Test
    void seedDefaultTagsIfEmpty_isNoOpWhenTableHasRows() {
        when(repo.count()).thenReturn(10L);

        service.seedDefaultTagsIfEmpty();

        verify(repo, never()).save(any(ParcelTag.class));
    }

    @Test
    void listGroupedActive_groupsTagsByCategoryPreservingOrder() {
        // Repo already sorts ORDER BY category ASC, sort_order ASC.
        ParcelTag t1 = ParcelTag.builder().id(1L).code("A").label("A").category("Cat1").sortOrder(1).active(true).build();
        ParcelTag t2 = ParcelTag.builder().id(2L).code("B").label("B").category("Cat1").sortOrder(2).active(true).build();
        ParcelTag t3 = ParcelTag.builder().id(3L).code("C").label("C").category("Cat2").sortOrder(1).active(true).build();
        when(repo.findByActiveTrueOrderByCategoryAscSortOrderAsc())
                .thenReturn(List.of(t1, t2, t3));

        List<ParcelTagGroupResponse> groups = service.listGroupedActive();

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).category()).isEqualTo("Cat1");
        assertThat(groups.get(0).tags()).hasSize(2);
        assertThat(groups.get(0).tags().get(0).code()).isEqualTo("A");
        assertThat(groups.get(0).tags().get(1).code()).isEqualTo("B");
        assertThat(groups.get(1).category()).isEqualTo("Cat2");
        assertThat(groups.get(1).tags()).hasSize(1);
        assertThat(groups.get(1).tags().get(0).code()).isEqualTo("C");
    }

    @Test
    void listGroupedActive_emptyRepo_returnsEmptyList() {
        when(repo.findByActiveTrueOrderByCategoryAscSortOrderAsc())
                .thenReturn(List.of());

        assertThat(service.listGroupedActive()).isEmpty();
    }
}
