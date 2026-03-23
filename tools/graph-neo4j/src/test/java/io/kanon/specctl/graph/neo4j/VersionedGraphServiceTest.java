package io.kanon.specctl.graph.neo4j;

import io.kanon.specctl.core.SpecCompiler;
import io.kanon.specctl.core.extract.ExtractionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VersionedGraphServiceTest {
    @Test
    void buildsVersionedGraphStatements() {
        SpecCompiler compiler = new SpecCompiler();
        var artifact = compiler.compile(TestFixtures.specFile());
        ExtractionResult extraction = new ExtractionResult(List.of(), List.of(), 1.0d, List.of());

        List<String> statements = new VersionedGraphService().ingestStatements("run-001", artifact.canonicalIr(), extraction);
        assertThat(statements).anyMatch(statement -> statement.contains("version: 'run-001'"));
        assertThat(statements).anyMatch(statement -> statement.contains("GeneratorRun"));
    }
}
