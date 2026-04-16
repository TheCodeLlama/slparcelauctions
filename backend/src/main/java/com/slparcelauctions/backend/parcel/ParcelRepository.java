package com.slparcelauctions.backend.parcel;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ParcelRepository extends JpaRepository<Parcel, Long> {
    Optional<Parcel> findBySlParcelUuid(UUID slParcelUuid);
}
