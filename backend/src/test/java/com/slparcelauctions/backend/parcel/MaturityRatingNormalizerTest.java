package com.slparcelauctions.backend.parcel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MaturityRatingNormalizerTest {

    @ParameterizedTest
    @CsvSource({
            "PG, GENERAL",
            "Mature, MODERATE",
            "Adult, ADULT"
    })
    void normalize_exactXmlCasings_mapToCanonical(String xmlValue, String expected) {
        assertThat(MaturityRatingNormalizer.normalize(xmlValue)).isEqualTo(expected);
    }

    @Test
    void normalize_null_throws() {
        assertThatThrownBy(() -> MaturityRatingNormalizer.normalize(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maturityRating");
    }

    @Test
    void normalize_blank_throws() {
        assertThatThrownBy(() -> MaturityRatingNormalizer.normalize(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MaturityRatingNormalizer.normalize("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalize_unknownValue_throws() {
        assertThatThrownBy(() -> MaturityRatingNormalizer.normalize("Teen"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Teen");
    }

    @Test
    void normalize_wrongCasingOnKnownKey_throws() {
        // Normalizer asserts exact XML casing — "mature" lowercase is a bug in the
        // upstream response shape and should fail loudly rather than be silently
        // normalized. This is the canary that protects us from SL quietly
        // changing its casing invariant.
        assertThatThrownBy(() -> MaturityRatingNormalizer.normalize("mature"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
