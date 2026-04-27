package com.slparcelauctions.backend.admin.ban;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BanRepository extends JpaRepository<Ban, Long> {

    /**
     * Returns active bans that cover the given IP address.
     * Covers {@code BanType.IP} and {@code BanType.BOTH}.
     */
    @Query("""
        SELECT b FROM Ban b
        WHERE b.liftedAt IS NULL
          AND (b.expiresAt IS NULL OR b.expiresAt > :now)
          AND (b.banType = com.slparcelauctions.backend.admin.ban.BanType.IP
               OR b.banType = com.slparcelauctions.backend.admin.ban.BanType.BOTH)
          AND b.ipAddress = :ip
        """)
    List<Ban> findActiveByIp(@Param("ip") String ip, @Param("now") OffsetDateTime now);

    /**
     * Returns active bans that cover the given SL avatar UUID.
     * Covers {@code BanType.AVATAR} and {@code BanType.BOTH}.
     */
    @Query("""
        SELECT b FROM Ban b
        WHERE b.liftedAt IS NULL
          AND (b.expiresAt IS NULL OR b.expiresAt > :now)
          AND (b.banType = com.slparcelauctions.backend.admin.ban.BanType.AVATAR
               OR b.banType = com.slparcelauctions.backend.admin.ban.BanType.BOTH)
          AND b.slAvatarUuid = :uuid
        """)
    List<Ban> findActiveByAvatar(@Param("uuid") UUID uuid, @Param("now") OffsetDateTime now);

    /**
     * Returns all currently-active bans (paged) for the admin list view.
     */
    @Query("""
        SELECT b FROM Ban b
        WHERE b.liftedAt IS NULL
          AND (b.expiresAt IS NULL OR b.expiresAt > :now)
        """)
    Page<Ban> findActive(@Param("now") OffsetDateTime now, Pageable pageable);

    /**
     * Returns all historical (lifted or expired) bans (paged) for the admin history view.
     */
    @Query("""
        SELECT b FROM Ban b
        WHERE b.liftedAt IS NOT NULL
           OR (b.expiresAt IS NOT NULL AND b.expiresAt <= :now)
        """)
    Page<Ban> findHistory(@Param("now") OffsetDateTime now, Pageable pageable);
}
