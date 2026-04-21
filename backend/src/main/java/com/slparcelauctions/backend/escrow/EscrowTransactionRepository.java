package com.slparcelauctions.backend.escrow;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EscrowTransactionRepository extends JpaRepository<EscrowTransaction, Long> {

    List<EscrowTransaction> findByEscrowIdOrderByCreatedAtAsc(Long escrowId);

    Optional<EscrowTransaction> findFirstBySlTransactionIdAndType(
            String slTransactionId, EscrowTransactionType type);
}
