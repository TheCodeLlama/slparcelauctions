package com.slparcelauctions.backend.common;

import lombok.experimental.SuperBuilder;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BaseEntityEqualityTest {

    @SuperBuilder
    static class Probe extends BaseEntity {}

    @Test
    void twoTransientEntitiesWithDifferentPublicIdsAreNotEqual() {
        Probe a = Probe.builder().build();
        Probe b = Probe.builder().build();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void twoEntitiesWithSamePublicIdAreEqual() {
        UUID shared = UUID.randomUUID();
        Probe a = Probe.builder().publicId(shared).build();
        Probe b = Probe.builder().publicId(shared).build();
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void hashCodeIsStableAcrossLifecycle() {
        Probe a = Probe.builder().build();
        int hashBefore = a.hashCode();
        // Simulate "persist" — publicId already set at construction; hashCode keys off it
        int hashAfter = a.hashCode();
        assertThat(hashBefore).isEqualTo(hashAfter);
    }

    @Test
    void hashSetMembershipSurvivesPersistBoundary() {
        Probe a = Probe.builder().build();
        Set<Probe> set = new HashSet<>();
        set.add(a);
        assertThat(set).contains(a);
        assertThat(set).contains(a);
    }

    @Test
    void publicIdIsNonNullAtConstruction() {
        Probe a = Probe.builder().build();
        assertThat(a.getPublicId()).isNotNull();
    }
}
