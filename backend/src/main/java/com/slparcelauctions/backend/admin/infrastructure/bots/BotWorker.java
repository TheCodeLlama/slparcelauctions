package com.slparcelauctions.backend.admin.infrastructure.bots;

import com.slparcelauctions.backend.common.BaseMutableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.OffsetDateTime;

@Entity
@Table(name = "bot_workers", uniqueConstraints = {
    @UniqueConstraint(columnNames = "name"),
    @UniqueConstraint(columnNames = "sl_uuid")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class BotWorker extends BaseMutableEntity {

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "sl_uuid", nullable = false, length = 36)
    private String slUuid;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;
}
