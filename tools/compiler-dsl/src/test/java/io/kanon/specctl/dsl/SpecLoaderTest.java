package io.kanon.specctl.dsl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpecLoaderTest {
    private final SpecLoader loader = new SpecLoader();

    @Test
    void loadsSampleSpec() {
        SpecDocument spec = loader.loadSpec(TestFixtures.specFile());

        assertThat(spec.service().name()).isEqualTo("test-service");
        assertThat(spec.service().basePackage()).isEqualTo("io.kanon.fixture");
        assertThat(spec.boundedContexts()).hasSize(1);
    }

    @Test
    void loadsSampleMigrations() {
        MigrationDocument migrations = loader.loadMigrations(TestFixtures.migrationsFile());

        assertThat(migrations.migrations()).hasSize(2);
    }
}
