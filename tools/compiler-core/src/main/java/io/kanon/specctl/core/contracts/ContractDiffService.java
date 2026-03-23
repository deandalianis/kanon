package io.kanon.specctl.core.contracts;

import io.kanon.specctl.core.SpecCompiler;
import io.kanon.specctl.core.json.JsonSupport;
import io.kanon.specctl.core.platform.PlatformTypes;
import io.kanon.specctl.core.plugin.PluginRuntime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ContractDiffService {
    private final SpecCompiler compiler = new SpecCompiler();

    public PlatformTypes.ContractDiff diff(Path specPath, Path baselineContractsDir) {
        throw new UnsupportedOperationException("Use the capability-aware diff overload");
    }

    public PlatformTypes.ContractDiff diff(Path specPath, Path baselineContractsDir, List<PluginRuntime.RegisteredPlugin> plugins) {
        try {
            Path tempDir = Files.createTempDirectory("kanon-contracts");
            compiler.generate(specPath, tempDir, plugins);
            Path generatedContracts = tempDir.resolve("src/generated/resources/contracts");
            Set<String> generatedOps = readOperations(generatedContracts.resolve("openapi.json"));
            Set<String> baselineOps = Files.exists(baselineContractsDir.resolve("openapi.json"))
                    ? readOperations(baselineContractsDir.resolve("openapi.json"))
                    : Set.of();
            Set<String> generatedSchemas = readSchemaNames(generatedContracts.resolve("events"));
            Set<String> baselineSchemas = readSchemaNames(baselineContractsDir.resolve("events"));
            return new PlatformTypes.ContractDiff(
                    difference(generatedOps, baselineOps),
                    difference(baselineOps, generatedOps),
                    symmetricDifference(generatedSchemas, baselineSchemas)
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to diff contracts", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> readOperations(Path openApiPath) throws IOException {
        if (!Files.exists(openApiPath)) {
            return Set.of();
        }
        Map<String, Object> document = JsonSupport.jsonMapper().readValue(Files.readString(openApiPath), Map.class);
        Object raw = document.get("operations");
        if (!(raw instanceof List<?> operations)) {
            return Set.of();
        }
        Set<String> values = new HashSet<>();
        for (Object operation : operations) {
            if (operation instanceof Map<?, ?> map) {
                values.add(map.get("method") + " " + map.get("path"));
            }
        }
        return values;
    }

    private Set<String> readSchemaNames(Path eventsDir) throws IOException {
        if (!Files.exists(eventsDir)) {
            return Set.of();
        }
        try (var walk = Files.walk(eventsDir, 1)) {
            Set<String> values = new HashSet<>();
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                values.add(file.getFileName().toString());
            }
            return values;
        }
    }

    private List<String> difference(Set<String> left, Set<String> right) {
        return left.stream().filter(value -> !right.contains(value)).sorted().toList();
    }

    private List<String> symmetricDifference(Set<String> left, Set<String> right) {
        Set<String> values = new HashSet<>(left);
        for (String item : right) {
            if (!values.add(item)) {
                values.remove(item);
            }
        }
        return values.stream().sorted().toList();
    }
}
