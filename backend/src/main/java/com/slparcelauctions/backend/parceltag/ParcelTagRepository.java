package com.slparcelauctions.backend.parceltag;

import java.util.List;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ParcelTagRepository extends JpaRepository<ParcelTag, Long> {

    List<ParcelTag> findByActiveTrueOrderByCategoryAscSortOrderAsc();

    List<ParcelTag> findByCodeIn(Set<String> codes);
}
