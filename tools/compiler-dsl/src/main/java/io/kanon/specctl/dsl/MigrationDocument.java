package io.kanon.specctl.dsl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MigrationDocument(List<Migration> migrations) {
    public MigrationDocument {
        migrations = migrations == null ? List.of() : List.copyOf(migrations);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Migration(
            MigrationType type,
            String boundedContext,
            String aggregate,
            String entity,
            String command,
            String event,
            String from,
            String to
    ) {
    }

    public enum MigrationType {
        RENAME_FIELD,
        RENAME_AGGREGATE,
        RENAME_COMMAND,
        RENAME_EVENT,
        REWRITE_RULE_EXPRESSION
    }
}
