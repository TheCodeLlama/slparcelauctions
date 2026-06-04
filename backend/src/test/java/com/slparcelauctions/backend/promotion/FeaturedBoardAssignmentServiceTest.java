package com.slparcelauctions.backend.promotion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class FeaturedBoardAssignmentServiceTest {

    private final FeaturedBoardAssignmentService svc = new FeaturedBoardAssignmentService();

    @Test
    void empty_pool_picks_board_one_at_position_zero() {
        var result = svc.assign(/* slotCount */ 5, /* perBoardCounts */ Map.of());
        assertThat(result.boardIndex()).isEqualTo(1);
        assertThat(result.position()).isEqualTo(0);
    }

    @Test
    void unbalanced_pool_picks_lowest_loaded_board() {
        var counts = Map.of(1, 3, 2, 3, 3, 1, 4, 2, 5, 3);
        var result = svc.assign(5, counts);
        assertThat(result.boardIndex()).isEqualTo(3);
        assertThat(result.position()).isEqualTo(1);
    }

    @Test
    void tie_breaks_to_lowest_board_index() {
        var counts = Map.of(1, 2, 2, 1, 3, 1, 4, 2, 5, 1);
        var result = svc.assign(5, counts);
        assertThat(result.boardIndex()).isEqualTo(2);
        assertThat(result.position()).isEqualTo(1);
    }

    @Test
    void balanced_full_pool_picks_first_board() {
        var counts = Map.of(1, 3, 2, 3, 3, 3, 4, 3, 5, 3);
        var result = svc.assign(5, counts);
        assertThat(result.boardIndex()).isEqualTo(1);
        assertThat(result.position()).isEqualTo(3);
    }

    @Test
    void boards_not_in_counts_treated_as_empty() {
        var counts = Map.of(1, 5);
        var result = svc.assign(3, counts);
        assertThat(result.boardIndex()).isEqualTo(2);
        assertThat(result.position()).isEqualTo(0);
    }

    @Test
    void slotCount_one_always_returns_board_one() {
        var result = svc.assign(1, Map.of(1, 7));
        assertThat(result.boardIndex()).isEqualTo(1);
        assertThat(result.position()).isEqualTo(7);
    }
}
