package io.kanon.specctl.workbench.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LegacyLargeObjectMigration implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LegacyLargeObjectMigration.class);

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public LegacyLargeObjectMigration(
            DataSource dataSource,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate
    ) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!isPostgres()) {
            return;
        }
        List<LegacyColumn> columns = List.of(
                new LegacyColumn("KANON_projects", "capabilities_json"),
                new LegacyColumn("KANON_proposals", "payload_json"),
                new LegacyColumn("KANON_proposals", "audit_json"),
                new LegacyColumn("KANON_runs", "metadata_json"),
                new LegacyColumn("KANON_runs", "log_text")
        );
        transactionTemplate.executeWithoutResult(status -> columns.forEach(this::migrateColumn));
    }

    private boolean isPostgres() {
        try (Connection connection = dataSource.getConnection()) {
            return "PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to inspect database vendor", exception);
        }
    }

    private void migrateColumn(LegacyColumn column) {
        Integer migrated = jdbcTemplate.queryForObject("""
                WITH legacy AS (
                    SELECT ctid, (%1$s)::oid AS oid
                    FROM %2$s
                    WHERE %1$s ~ '^[0-9]+$'
                      AND EXISTS (
                          SELECT 1
                          FROM pg_largeobject_metadata metadata
                          WHERE metadata.oid = (%1$s)::oid
                      )
                ),
                updated AS (
                    UPDATE %2$s target
                    SET %1$s = convert_from(lo_get(legacy.oid), 'UTF8')
                    FROM legacy
                    WHERE target.ctid = legacy.ctid
                    RETURNING legacy.oid AS oid
                ),
                cleanup AS (
                    SELECT lo_unlink(oid)
                    FROM (SELECT DISTINCT oid FROM updated) migrated_oids
                )
                SELECT count(*)
                FROM updated
                """.formatted(column.columnName(), column.tableName()), Integer.class);
        if (migrated != null && migrated > 0) {
            log.info("Migrated {} legacy large-object values in {}.{}", migrated, column.tableName(), column.columnName());
        }
    }

    private record LegacyColumn(String tableName, String columnName) {
    }
}
