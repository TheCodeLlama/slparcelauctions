package com.slparcelauctions.backend.parceltag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.parceltag.dto.ParcelTagGroupResponse;

class ParcelTagServiceTest {

    private ParcelTagRepository repo;
    private ParcelTagCategoryRepository categoryRepo;
    private ParcelTagService service;

    @BeforeEach
    void setup() {
        repo = mock(ParcelTagRepository.class);
        categoryRepo = mock(ParcelTagCategoryRepository.class);
        service = new ParcelTagService(repo, categoryRepo);
    }

    @Test
    void seedDefaultTagsIfEmpty_runsInsertsWhenTableEmpty() {
        when(repo.count()).thenReturn(0L);
        // Categories are looked up by derived code; return Optional.empty so
        // the seed creates them.
        when(categoryRepo.findByCode(anyString())).thenReturn(Optional.empty());
        // Re-route the categoryRepo.save → return arg so subsequent computeIfAbsent
        // picks the cached map entry rather than re-saving on each call.
        Map<String, ParcelTagCategory> savedCats = new HashMap<>();
        when(categoryRepo.save(any(ParcelTagCategory.class))).thenAnswer(inv -> {
            ParcelTagCategory c = inv.getArgument(0);
            savedCats.put(c.getCode(), c);
            return c;
        });

        service.seedDefaultTagsIfEmpty();

        // 25 inserts — one per canonical tag row.
        verify(repo, times(25)).save(any(ParcelTag.class));
        // 6 categories created via the seed-on-first-encounter path.
        verify(categoryRepo, times(6)).save(any(ParcelTagCategory.class));
        verify(categoryRepo, atLeastOnce()).findByCode(anyString());
    }

    @Test
    void seedDefaultTagsIfEmpty_isNoOpWhenTableHasRows() {
        when(repo.count()).thenReturn(10L);

        service.seedDefaultTagsIfEmpty();

        verify(repo, never()).save(any(ParcelTag.class));
        verify(categoryRepo, never()).save(any(ParcelTagCategory.class));
    }

    @Test
    void listGroupedActive_groupsTagsByCategoryLabel() {
        ParcelTagCategory cat1 = ParcelTagCategory.builder()
                .code("CAT1").label("Cat1").active(true).build();
        ParcelTagCategory cat2 = ParcelTagCategory.builder()
                .code("CAT2").label("Cat2").active(true).build();
        ParcelTag t1 = ParcelTag.builder().id(1L).code("A").label("A").category(cat1).active(true).build();
        ParcelTag t2 = ParcelTag.builder().id(2L).code("B").label("B").category(cat1).active(true).build();
        ParcelTag t3 = ParcelTag.builder().id(3L).code("C").label("C").category(cat2).active(true).build();
        when(repo.findByActiveTrueAndCategoryActiveTrueOrderByCategory_LabelAscLabelAsc())
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
        when(repo.findByActiveTrueAndCategoryActiveTrueOrderByCategory_LabelAscLabelAsc())
                .thenReturn(List.of());

        assertThat(service.listGroupedActive()).isEmpty();
    }
}
