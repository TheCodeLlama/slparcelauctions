package com.slparcelauctions.backend.region;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RegionRepository extends JpaRepository<Region, Long> {

    Optional<Region> findBySlUuid(UUID slUuid);

    Optional<Region> findByNameIgnoreCase(String name);
}
