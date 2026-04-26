package com.slparcelauctions.backend.parcel.dev;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Integration test for the dev-profile {@link MaturityRatingDevTouchUp} bean.
 *
 * <p>Runs inside a {@code @Transactional} test fixture, so seed rows are
 * visible to the touch-up's SQL UPDATEs (same transaction). The touch-up
 * uses raw JDBC, which bypasses Hibernate's L1 cache — we explicitly
 * {@link EntityManager#clear() clear} the persistence context before
 * reloading so the assertions read fresh DB state, not stale cached
 * entities.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false"
})
@Transactional
class MaturityRatingDevTouchUpTest {

    @Autowired ParcelRepository parcelRepository;
    @Autowired MaturityRatingDevTouchUp touchUp;

    @PersistenceContext EntityManager em;

    @Test
    void rewrites_legacy_values_caseInsensitively() {
        Parcel legacyPg = parcelWithMaturity("PG");
        Parcel legacyMature = parcelWithMaturity("Mature");
        Parcel legacyAdult = parcelWithMaturity("Adult");
        Parcel alreadyCanonical = parcelWithMaturity("GENERAL");

        touchUp.runOnce();

        assertThat(reload(legacyPg).getMaturityRating()).isEqualTo("GENERAL");
        assertThat(reload(legacyMature).getMaturityRating()).isEqualTo("MODERATE");
        assertThat(reload(legacyAdult).getMaturityRating()).isEqualTo("ADULT");
        assertThat(reload(alreadyCanonical).getMaturityRating()).isEqualTo("GENERAL");
    }

    @Test
    void idempotent_onSecondRun() {
        Parcel legacyPg = parcelWithMaturity("PG");
        touchUp.runOnce();
        touchUp.runOnce();
        assertThat(reload(legacyPg).getMaturityRating()).isEqualTo("GENERAL");
    }

    private Parcel parcelWithMaturity(String raw) {
        Parcel p = Parcel.builder()
                .slParcelUuid(UUID.randomUUID())
                .regionName("TestRegion-" + UUID.randomUUID())
                .areaSqm(1024)
                .maturityRating(raw)
                .verified(true)
                .build();
        return parcelRepository.saveAndFlush(p);
    }

    private Parcel reload(Parcel p) {
        em.clear();
        return parcelRepository.findById(p.getId()).orElseThrow();
    }
}
