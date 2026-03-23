package io.kanon.specctl.dsl;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SpecLoader {
    private static final ObjectMapper YAML_MAPPER = build(new ObjectMapper(new YAMLFactory()));

    public SpecDocument loadSpec(Path specPath) {
        try {
            return YAML_MAPPER.readValue(Files.readString(specPath), SpecDocument.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load spec from " + specPath, exception);
        }
    }

    public MigrationDocument loadMigrations(Path migrationPath) {
        try {
            return YAML_MAPPER.readValue(Files.readString(migrationPath), MigrationDocument.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load migrations from " + migrationPath, exception);
        }
    }

    private static ObjectMapper build(ObjectMapper mapper) {
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
        mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return mapper;
    }
}
