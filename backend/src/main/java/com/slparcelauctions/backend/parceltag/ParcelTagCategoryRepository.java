package com.slparcelauctions.backend.parceltag;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ParcelTagCategoryRepository extends JpaRepository<ParcelTagCategory, Long> {

    Optional<ParcelTagCategory> findByCode(String code);

    boolean existsByCode(String code);

    /** Admin view — returns inactive rows too. */
    List<ParcelTagCategory> findAllByOrderByLabelAsc();

    /** Active-only. */
    List<ParcelTagCategory> findByActiveTrueOrderByLabelAsc();
}
