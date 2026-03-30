package io.kanon.specctl.core.plugin;

import io.kanon.specctl.core.SpecCompiler;
import io.kanon.specctl.core.TestFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class PluginRuntimeTest {
    @TempDir
    Path tempDir;

    @Test
    void builtInPluginsGenerateDeterministicOutputs() throws Exception {
        SpecCompiler compiler = new SpecCompiler();
        compiler.generate(TestFixtures.specFile(), tempDir, BuiltinPlugins.defaults());

        Path handler;
        try (var walk = Files.walk(tempDir.resolve("src/generated/java/runtime"))) {
            handler = walk
                    .filter(path -> path.getFileName().toString().endsWith("CommandHandler.java"))
                    .findFirst()
                    .orElseThrow();
        }
        Path contract = tempDir.resolve("src/generated/resources/contracts/openapi.json");
        assertThat(Files.exists(handler)).isTrue();
        assertThat(Files.exists(contract)).isTrue();
        String firstOpenApi = Files.readString(contract);

        compiler.generate(TestFixtures.specFile(), tempDir, BuiltinPlugins.defaults());
        String secondOpenApi = Files.readString(contract);
        assertThat(firstOpenApi).isEqualTo(secondOpenApi);
    }
}
