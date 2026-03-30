package io.kanon.specctl.graph.neo4j;

import io.kanon.specctl.core.SpecCompiler;
import io.kanon.specctl.extraction.ir.CodebaseIr;
import io.kanon.specctl.extraction.ir.ProjectCapabilities;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class VersionedGraphServiceTest {
    @Test
    void buildsVersionedGraphStatements() {
        SpecCompiler compiler = new SpecCompiler();
        var artifact = compiler.compile(TestFixtures.specFile());
        CodebaseIr extraction = new CodebaseIr(
                1,
                "0.1.0",
                "/tmp/project",
                null,
                ProjectCapabilities.minimal(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        List<String> statements =
                new VersionedGraphService().ingestStatements("run-001", artifact.canonicalIr(), extraction);
        assertThat(statements).anyMatch(statement -> statement.contains("version: 'run-001'"));
        assertThat(statements).anyMatch(statement -> statement.contains("GeneratorRun"));
    }
}
