package com.slparcelauctions.backend.parceltag;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ParcelTagRepository extends JpaRepository<ParcelTag, Long> {

    List<ParcelTag> findByActiveTrueOrderByCategoryAscSortOrderAsc();

    List<ParcelTag> findByCodeIn(Set<String> codes);

    Optional<ParcelTag> findByCode(String code);

    boolean existsByCode(String code);

    /** Admin view — returns inactive rows too. */
    List<ParcelTag> findAllByOrderByCategoryAscSortOrderAsc();

    /** Largest sortOrder within a category, or 0 if the category has no tags yet. */
    @Query("select coalesce(max(t.sortOrder), 0) from ParcelTag t where t.category = :category")
    int findMaxSortOrderByCategory(String category);
}
