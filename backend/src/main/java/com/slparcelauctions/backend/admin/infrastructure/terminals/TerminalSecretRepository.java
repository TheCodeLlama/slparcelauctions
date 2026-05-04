package com.slparcelauctions.backend.admin.infrastructure.terminals;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TerminalSecretRepository extends JpaRepository<TerminalSecret, Long> {
    List<TerminalSecret> findByRetiredAtIsNullOrderBySecretVersionDesc();
    Optional<TerminalSecret> findTopByOrderBySecretVersionDesc();
}
