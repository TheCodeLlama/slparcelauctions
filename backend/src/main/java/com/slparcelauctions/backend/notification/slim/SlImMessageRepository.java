package com.slparcelauctions.backend.notification.slim;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SlImMessageRepository extends JpaRepository<SlImMessage, Long> {

    @Query(value = """
        SELECT * FROM sl_im_message
        WHERE status = 'PENDING'
        ORDER BY created_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<SlImMessage> pollPending(@Param("limit") int limit);

    @Query("SELECT m.status, count(m) FROM SlImMessage m GROUP BY m.status")
    List<Object[]> countByStatus();
}
