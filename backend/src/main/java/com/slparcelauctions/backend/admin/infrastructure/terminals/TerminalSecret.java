package com.slparcelauctions.backend.admin.infrastructure.terminals;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.common.BaseMutableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "terminal_secrets",
       uniqueConstraints = @UniqueConstraint(columnNames = "secret_version"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class TerminalSecret extends BaseMutableEntity {

    @Column(name = "secret_version", nullable = false)
    private Integer secretVersion;

    @Column(name = "secret_value", nullable = false, length = 64)
    private String secretValue;

    @Column(name = "retired_at")
    private OffsetDateTime retiredAt;
}
