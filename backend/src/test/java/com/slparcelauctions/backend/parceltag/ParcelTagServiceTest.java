package com.slparcelauctions.backend.parceltag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
