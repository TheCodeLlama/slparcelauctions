package com.slparcelauctions.backend.parceltag;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ParcelTagRepository extends JpaRepository<ParcelTag, Long> {

    /** Public view: only tags whose own active flag AND whose category's active flag are both true. */
    List<ParcelTag> findByActiveTrueAndCategoryActiveTrueOrderByCategory_LabelAscLabelAsc();

    List<ParcelTag> findByCodeIn(Set<String> codes);

    Optional<ParcelTag> findByCode(String code);

    boolean existsByCode(String code);

    /** Admin view — returns all tags (incl. inactive) regardless of category state. */
    List<ParcelTag> findAllByOrderByCategory_LabelAscLabelAsc();
}
