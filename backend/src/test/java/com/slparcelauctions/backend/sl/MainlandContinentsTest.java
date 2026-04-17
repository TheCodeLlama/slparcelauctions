package com.slparcelauctions.backend.sl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MainlandContinentsTest {

    @Test
    void sansaraCenter_isMainland() {
        // Sansara box: 254208.0, 265984.0, 250368.0, 259328.0
        assertThat(MainlandContinents.isMainland(260000.0, 254000.0)).isTrue();
        assertThat(MainlandContinents.continentAt(260000.0, 254000.0)).hasValue("Sansara");
    }

    @Test
    void bellisseriaWestCoastCenter_isMainland() {
        // Bellisseria West Coast: 261888.0, 267776.0, 240640.0, 250368.0
        assertThat(MainlandContinents.isMainland(264000.0, 245000.0)).isTrue();
        assertThat(MainlandContinents.continentAt(264000.0, 245000.0)).hasValue("Bellisseria");
    }

    @Test
    void zindraCenter_isMainland() {
        // Zindra: 460032.0, 466432.0, 301824.0, 307456.0
        assertThat(MainlandContinents.isMainland(463000.0, 304000.0)).isTrue();
        assertThat(MainlandContinents.continentAt(463000.0, 304000.0)).hasValue("Zindra");
    }

    @Test
    void horizonsCenter_isMainland() {
        // Horizons: 461824.0, 464384.0, 307456.0, 310016.0
        assertThat(MainlandContinents.isMainland(463000.0, 308500.0)).isTrue();
        assertThat(MainlandContinents.continentAt(463000.0, 308500.0)).hasValue("Horizons");
    }

    @Test
    void farAwayFromAllContinents_isNotMainland() {
        assertThat(MainlandContinents.isMainland(100000.0, 100000.0)).isFalse();
        assertThat(MainlandContinents.continentAt(100000.0, 100000.0)).isEmpty();
    }

    @Test
    void justOutsideSansaraEastBoundary_isNotMainland() {
        // Sansara east edge is 265984.0; just past it
        assertThat(MainlandContinents.isMainland(265984.0, 255000.0)).isFalse();
    }

    @Test
    void sansaraExactWestBoundary_isMainland() {
        // Half-open interval: x1 inclusive, x2 exclusive
        assertThat(MainlandContinents.isMainland(254208.0, 255000.0)).isTrue();
    }
}
