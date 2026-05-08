package com.slparcelauctions.backend.parceltag;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ParcelTagRepository extends JpaRepository<ParcelTag, Long> {

    List<ParcelTag> findByActiveTrueOrderByCategoryAscLabelAsc();

    List<ParcelTag> findByCodeIn(Set<String> codes);

    Optional<ParcelTag> findByCode(String code);

    boolean existsByCode(String code);

    /** Admin view — returns inactive rows too. */
    List<ParcelTag> findAllByOrderByCategoryAscLabelAsc();
}
