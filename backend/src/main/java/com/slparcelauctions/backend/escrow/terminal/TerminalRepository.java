package com.slparcelauctions.backend.escrow.terminal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TerminalRepository extends JpaRepository<Terminal, String> {

    @Query("""
            SELECT t FROM Terminal t
            WHERE t.active = true AND t.lastSeenAt >= :cutoff
            ORDER BY t.lastSeenAt DESC
            """)
    List<Terminal> findLiveTerminals(@Param("cutoff") OffsetDateTime cutoff);

    default Optional<Terminal> findAnyLive(OffsetDateTime cutoff) {
        List<Terminal> live = findLiveTerminals(cutoff);
        return live.isEmpty() ? Optional.empty() : Optional.of(live.get(0));
    }
}
