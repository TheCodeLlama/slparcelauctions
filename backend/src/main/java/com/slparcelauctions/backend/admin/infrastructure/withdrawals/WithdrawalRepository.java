package com.slparcelauctions.backend.admin.infrastructure.withdrawals;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface WithdrawalRepository extends JpaRepository<Withdrawal, Long> {

    @Query("select coalesce(sum(w.amount), 0) from Withdrawal w where w.status = 'PENDING'")
    long sumPending();

    Optional<Withdrawal> findByTerminalCommandId(Long terminalCommandId);

    Page<Withdrawal> findAllByOrderByRequestedAtDesc(Pageable pageable);
}
