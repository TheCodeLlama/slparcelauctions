package com.slparcelauctions.backend.escrow;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EscrowRepository extends JpaRepository<Escrow, Long> {

    Optional<Escrow> findByAuctionId(Long auctionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Escrow e WHERE e.id = :id")
    Optional<Escrow> findByIdForUpdate(@Param("id") Long id);
}
