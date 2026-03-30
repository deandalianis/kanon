package io.kanon.specctl.core.migration;

import io.kanon.specctl.core.TestFixtures;
import io.kanon.specctl.dsl.MigrationDocument;
import io.kanon.specctl.dsl.SpecDocument;
import io.kanon.specctl.dsl.SpecLoader;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MigrationServiceTest {
    @Test
    void appliesForwardOnlyRenamesDeterministically() {
        SpecLoader loader = new SpecLoader();
        SpecDocument spec = loader.loadSpec(TestFixtures.specFile());
        MigrationDocument migrations = loader.loadMigrations(TestFixtures.migrationsFile());

        MigrationService.MigrationOutcome outcome = new MigrationService().apply(spec, migrations, false);
        SpecDocument.Field renamedField = outcome.updatedSpec()
                .boundedContexts().getFirst()
                .aggregates().getFirst()
                .entities().getFirst()
                .fields().stream()
                .filter(field -> field.name().equals("staffId"))
                .findFirst()
                .orElseThrow();

        assertThat(renamedField.name()).isEqualTo("staffId");
        assertThat(outcome.actions()).hasSize(2);
    }
}
