package com.slparcelauctions.backend.admin.infrastructure.terminals;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

@Entity
@Table(name = "terminal_secrets",
       uniqueConstraints = @UniqueConstraint(columnNames = "version"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TerminalSecret {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "secret_value", nullable = false, length = 64)
    private String secretValue;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "retired_at")
    private OffsetDateTime retiredAt;
}
