package com.slparcelauctions.backend.common;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Refreshes a Postgres CHECK constraint on a column-backed enum so that rows
 * inserted with values added to the Java enum since {@code ddl-auto: update}
 * initially built the constraint are no longer rejected.
 *
 * <p>Hibernate's {@code ddl-auto: update} mode creates the initial CHECK
 * constraint from the enum values it sees at first boot; it does NOT re-emit
 * the check when the Java enum grows. This helper DROPs the stale CHECK and
 * recreates it from {@code Enum.values()} at runtime — fed directly from the
 * Java source of truth — so adding a new enum value does not require an extra
 * bookkeeping step to keep the DDL in sync.
 *
 * <p>Callers register one {@code @Component} per (table, column, enum) tuple
 * and invoke {@link #sync} from an
 * {@link org.springframework.context.event.EventListener @EventListener}-annotated
 * method on {@link org.springframework.boot.context.event.ApplicationReadyEvent}.
 * Execution is idempotent — re-running after the constraint is already
 * current is a no-op in terms of data integrity (Postgres does a fresh scan
 * of the column against the new predicate, which must pass because the new
 * predicate is a superset of the old one).
 */
@RequiredArgsConstructor
@Slf4j
public class EnumCheckConstraintSync {

    private final JdbcTemplate jdbc;

    public <E extends Enum<E>> void sync(String table, String column, Class<E> enumType) {
        String constraintName = table + "_" + column + "_check";
        E[] constants = enumType.getEnumConstants();
        String dropSql = "ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS " + constraintName;
        jdbc.execute(dropSql);
        if (constants.length == 0) {
            // Empty enum -- Postgres "IN ()" is a parse error. The empty-enum
            // case currently arises for BotTaskType after the ownership-only
            // verification refactor: the column is kept for future-extension
            // task types but no enum value exists today. Leaving the
            // constraint dropped is harmless because nothing inserts into
            // bot_tasks until a future task type is added; that future task
            // type will re-introduce enum values and the next boot re-creates
            // the constraint from them.
            log.info("Skipped {} CHECK constraint -- {} has no enum values",
                    constraintName, enumType.getSimpleName());
            return;
        }
        String valuesList = Arrays.stream(constants)
                .map(e -> "'" + e.name() + "'")
                .collect(Collectors.joining(", "));
        String addSql = "ALTER TABLE " + table + " ADD CONSTRAINT " + constraintName
                + " CHECK (" + column + " IN (" + valuesList + "))";
        jdbc.execute(addSql);
        log.info("Refreshed {} CHECK constraint to {} values from {}",
                constraintName, constants.length, enumType.getSimpleName());
    }
}
