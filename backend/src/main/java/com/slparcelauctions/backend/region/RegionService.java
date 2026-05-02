package com.slparcelauctions.backend.region;

import java.util.Objects;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.parcel.MaturityRating;
import com.slparcelauctions.backend.region.dto.RegionPageData;
import com.slparcelauctions.backend.sl.MainlandContinents;
import com.slparcelauctions.backend.sl.exception.NotMainlandException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Idempotent upsert for {@link Region}. Called from the parcel-lookup flow
 * with the parsed region page data; INSERTs a new row on first sight, UPDATEs
 * an existing row in place when SL has shifted any of name / grid coords /
 * maturity since we last fetched it.
 *
 * <p>Mainland gate: regions whose grid coordinates fall outside every
 * Mainland continent bounding box are rejected with {@link NotMainlandException}
 * before any DB write — non-Mainland regions are never persisted (Phase 1
 * supports Mainland only). The SL region page returns coords in region units
 * but {@code MainlandContinents} is keyed on world meters, so we multiply by
 * 256 at the call site.
 *
 * <p>Race handling: a {@code DataIntegrityViolationException} on the
 * {@code sl_uuid} unique constraint at INSERT time means another thread won
 * the create. We re-SELECT and return the winning row. A name-uniqueness
 * collision during UPDATE (e.g., a region was renamed to one we already know
 * by another UUID) is rare on Mainland and is allowed to bubble up as a 500.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RegionService {

    private static final double METERS_PER_REGION = 256.0;

    private final RegionRepository repo;

    @Transactional
    public Region upsert(RegionPageData data) {
        String canonicalMaturity = MaturityRating.fromSlCode(data.maturityRaw());

        double meterX = data.gridX() * METERS_PER_REGION;
        double meterY = data.gridY() * METERS_PER_REGION;
        MainlandContinents.continentAt(meterX, meterY)
                .orElseThrow(() -> new NotMainlandException(data.gridX(), data.gridY()));

        Optional<Region> existing = repo.findBySlUuid(data.slUuid());
        if (existing.isEmpty()) {
            // Same region name may already exist under a different sl_uuid if
            // an earlier ingest stubbed a different uuid for the same SL
            // region — return that row and update its sl_uuid in place rather
            // than colliding on the regions_name_key constraint.
            existing = repo.findByNameIgnoreCase(data.name());
        }
        if (existing.isPresent()) {
            return refreshIfChanged(existing.get(), data, canonicalMaturity);
        }

        Region candidate = Region.builder()
                .slUuid(data.slUuid())
                .name(data.name())
                .gridX(data.gridX())
                .gridY(data.gridY())
                .maturityRating(canonicalMaturity)
                .build();
        try {
            Region saved = repo.saveAndFlush(candidate);
            log.info("Region inserted: id={} sl_uuid={} name={} grid=({},{}) mat={}",
                    saved.getId(), saved.getSlUuid(), saved.getName(),
                    saved.getGridX(), saved.getGridY(), saved.getMaturityRating());
            return saved;
        } catch (DataIntegrityViolationException e) {
            // Concurrent insert won the race on the sl_uuid unique constraint.
            // Re-SELECT to return the winning row rather than retrying.
            return repo.findBySlUuid(data.slUuid())
                    .orElseThrow(() -> new IllegalStateException(
                            "Race-loss couldn't re-find region " + data.slUuid(), e));
        }
    }

    private Region refreshIfChanged(Region row, RegionPageData data, String canonicalMaturity) {
        boolean changed = false;
        if (!Objects.equals(row.getSlUuid(), data.slUuid())) {
            row.setSlUuid(data.slUuid());
            changed = true;
        }
        if (!Objects.equals(row.getName(), data.name())) {
            row.setName(data.name());
            changed = true;
        }
        if (Double.compare(row.getGridX(), data.gridX()) != 0) {
            row.setGridX(data.gridX());
            changed = true;
        }
        if (Double.compare(row.getGridY(), data.gridY()) != 0) {
            row.setGridY(data.gridY());
            changed = true;
        }
        if (!Objects.equals(row.getMaturityRating(), canonicalMaturity)) {
            row.setMaturityRating(canonicalMaturity);
            changed = true;
        }
        if (changed) {
            log.info("Region refreshed from SL: id={} sl_uuid={} name={} grid=({},{}) mat={}",
                    row.getId(), row.getSlUuid(), row.getName(),
                    row.getGridX(), row.getGridY(), row.getMaturityRating());
            return repo.save(row);
        }
        return row;
    }
}
