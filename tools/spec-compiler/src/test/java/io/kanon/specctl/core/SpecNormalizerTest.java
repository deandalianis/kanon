package io.kanon.specctl.core;

import io.kanon.specctl.ir.CanonicalIr;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SpecNormalizerTest {
    @Test
    void normalizesFixtureSpecIntoCanonicalIr() {
        SpecCompiler compiler = new SpecCompiler();
        SpecCompiler.CompilationArtifact artifact = compiler.compile(TestFixtures.specFile());

        CanonicalIr ir = artifact.canonicalIr();
        assertThat(ir.service().canonicalPath()).isEqualTo("/services/test-service");
        assertThat(ir.boundedContexts()).hasSize(1);
        assertThat(ir.boundedContexts().getFirst().aggregates()).hasSize(1);
        assertThat(ir.boundedContexts().getFirst().aggregates().getFirst().commands().getFirst().emittedEventPaths())
                .containsExactly(
                        "/services/test-service/bounded-contexts/core/aggregates/task-request/events/task-submitted");
    }

    @Test
    void stableIdsAreDeterministicAcrossRuns() {
        SpecCompiler compiler = new SpecCompiler();
        String first = compiler.compile(TestFixtures.specFile())
                .canonicalIr()
                .boundedContexts()
                .getFirst()
                .stableId();
        String second = compiler.compile(TestFixtures.specFile())
                .canonicalIr()
                .boundedContexts()
                .getFirst()
                .stableId();
        assertThat(first).isEqualTo(second);
    }
}
