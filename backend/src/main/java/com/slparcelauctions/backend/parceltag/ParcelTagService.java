package com.slparcelauctions.backend.parceltag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.parceltag.dto.ParcelTagGroupResponse;
import com.slparcelauctions.backend.parceltag.dto.ParcelTagResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Parcel tag read + seed service. On the first boot of a fresh database,
 * {@link #seedDefaultTagsIfEmpty()} inserts the 25 canonical tags defined
 * in spec §18 (Task 9, step 9.5). Subsequent boots are no-ops because the
 * seeder only runs when {@code repo.count() == 0}.
 *
 * <p>The {@code @EventListener(ApplicationReadyEvent)} trigger guarantees
 * the full context — including Flyway (when enabled) and all other
 * repositories — is initialized before the seed INSERTs run.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ParcelTagService {

    private final ParcelTagRepository repo;

    @Transactional(readOnly = true)
    public List<ParcelTagGroupResponse> listGroupedActive() {
        Map<String, List<ParcelTagResponse>> grouped = new LinkedHashMap<>();
        for (ParcelTag t : repo.findByActiveTrueOrderByCategoryAscSortOrderAsc()) {
            grouped.computeIfAbsent(t.getCategory(), k -> new ArrayList<>())
                    .add(ParcelTagResponse.from(t));
        }
        return grouped.entrySet().stream()
                .map(e -> new ParcelTagGroupResponse(e.getKey(), e.getValue()))
                .toList();
    }

    /**
     * Seeds the {@code parcel_tags} table on first boot with the 25 canonical
     * tag rows. Idempotent: short-circuits when any rows already exist.
     *
     * <p>Uses {@link ApplicationReadyEvent} rather than {@code @PostConstruct}
     * so the JPA and datasource init phases are fully complete before the
     * seed transaction opens.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedDefaultTagsIfEmpty() {
        if (repo.count() > 0) {
            return;
        }
        int order = 0;
        for (String[] row : SEED_DATA) {
            ParcelTag t = ParcelTag.builder()
                    .code(row[0])
                    .label(row[1])
                    .category(row[2])
                    .description(row[3])
                    .sortOrder(++order)
                    .active(true)
                    .build();
            repo.save(t);
        }
        log.info("Seeded {} default parcel tags on first boot.", SEED_DATA.length);
    }

    private static final String[][] SEED_DATA = {
            {"WATERFRONT", "Waterfront", "Terrain / Environment", "Ocean, lake, or river border"},
            {"SAILABLE", "Sailable", "Terrain / Environment", "Connected to navigable water (Linden waterways)"},
            {"GRASS", "Grass", "Terrain / Environment", "Grass terrain"},
            {"SNOW", "Snow", "Terrain / Environment", "Snow terrain"},
            {"SAND", "Sand", "Terrain / Environment", "Sand or beach terrain"},
            {"MOUNTAIN", "Mountain", "Terrain / Environment", "Elevated or hilly terrain"},
            {"FOREST", "Forest", "Terrain / Environment", "Wooded area"},
            {"FLAT", "Flat", "Terrain / Environment", "Level terrain, good for building"},
            {"STREETFRONT", "Streetfront", "Roads / Access", "Borders a Linden road"},
            {"ROADSIDE", "Roadside", "Roads / Access", "Near (but not directly on) a Linden road"},
            {"RAILWAY", "Railway", "Roads / Access", "Near Linden railroad / SLRR"},
            {"CORNER_LOT", "Corner Lot", "Location Features", "Parcel on a corner (two road or water sides)"},
            {"HILLTOP", "Hilltop", "Location Features", "Elevated with views"},
            {"ISLAND", "Island", "Location Features", "Surrounded by water"},
            {"PENINSULA", "Peninsula", "Location Features", "Water on three sides"},
            {"SHELTERED", "Sheltered", "Location Features", "Enclosed or private feeling, surrounded by terrain"},
            {"RESIDENTIAL", "Residential", "Neighbors / Context", "Residential neighborhood"},
            {"COMMERCIAL", "Commercial", "Neighbors / Context", "Commercial or shopping area"},
            {"INFOHUB_ADJACENT", "Infohub Adjacent", "Neighbors / Context", "Near a Linden infohub"},
            {"PROTECTED_LAND", "Protected Land", "Neighbors / Context", "Adjacent to Linden-owned protected land"},
            {"SCENIC", "Scenic", "Neighbors / Context", "Notable views or landscape"},
            {"HIGH_PRIM", "High Prim", "Parcel Features", "Higher-than-baseline land impact allowance"},
            {"MUSIC", "Music Stream", "Parcel Features", "Parcel has music stream URL set"},
            {"MEDIA", "Media Enabled", "Parcel Features", "Parcel has media URL set"},
            {"RARE", "Rare", "Miscellaneous", "Scarcity-based premium parcel type"}
    };
}
