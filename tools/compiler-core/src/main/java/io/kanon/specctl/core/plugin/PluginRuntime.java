package io.kanon.specctl.core.plugin;

import io.kanon.specctl.core.diagnostics.Diagnostics;
import io.kanon.specctl.ir.CanonicalIr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PluginRuntime {
    public List<GeneratedFile> generate(CanonicalIr ir, Path targetRoot, List<RegisteredPlugin> plugins, Diagnostics diagnostics) {
        List<RegisteredPlugin> ordered = sortPlugins(plugins, diagnostics);
        diagnostics.throwIfErrors();

        List<GeneratedFile> firstPass = runOnce(ir, ordered, diagnostics);
        List<GeneratedFile> secondPass = runOnce(ir, ordered, diagnostics);
        if (!firstPass.equals(secondPass)) {
            diagnostics.error("PLUGIN_NON_DETERMINISTIC", "Plugin output changed between identical runs", "/plugins");
            diagnostics.throwIfErrors();
        }
        materialize(targetRoot, ordered, firstPass);
        return firstPass;
    }

    private List<GeneratedFile> runOnce(CanonicalIr ir, List<RegisteredPlugin> ordered, Diagnostics diagnostics) {
        List<GeneratedFile> outputs = new ArrayList<>();
        for (RegisteredPlugin registeredPlugin : ordered) {
            if (registeredPlugin.plugin().order() != registeredPlugin.manifest().executionPhase().order()) {
                diagnostics.error(
                        "PLUGIN_ORDER_MISMATCH",
                        "Plugin order() must match manifest executionPhase order for " + registeredPlugin.manifest().name(),
                        "/plugins/" + registeredPlugin.manifest().name()
                );
                continue;
            }
            GenerationContext context = new GenerationContext(ir, registeredPlugin.manifest(), diagnostics);
            registeredPlugin.plugin().apply(context);
            outputs.addAll(context.generatedFiles());
        }
        outputs.sort(Comparator.comparing(file -> file.relativePath().toString()));
        return List.copyOf(outputs);
    }

    private List<RegisteredPlugin> sortPlugins(List<RegisteredPlugin> plugins, Diagnostics diagnostics) {
        Map<String, RegisteredPlugin> byName = new HashMap<>();
        Map<ExecutionPhase, List<RegisteredPlugin>> byPhase = new HashMap<>();
        for (RegisteredPlugin plugin : plugins) {
            byName.put(plugin.manifest().name(), plugin);
            byPhase.computeIfAbsent(plugin.manifest().executionPhase(), unused -> new ArrayList<>()).add(plugin);
        }
        List<RegisteredPlugin> ordered = new ArrayList<>();
        for (ExecutionPhase phase : ExecutionPhase.values()) {
            ordered.addAll(sortPhase(byPhase.getOrDefault(phase, List.of()), byName, diagnostics));
        }
        return ordered;
    }

    private List<RegisteredPlugin> sortPhase(
            List<RegisteredPlugin> plugins,
            Map<String, RegisteredPlugin> byName,
            Diagnostics diagnostics
    ) {
        Map<String, Set<String>> edges = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (RegisteredPlugin plugin : plugins) {
            indegree.put(plugin.manifest().name(), 0);
            edges.put(plugin.manifest().name(), new HashSet<>());
        }
        for (RegisteredPlugin plugin : plugins) {
            for (String dependency : plugin.manifest().dependencies()) {
                RegisteredPlugin dependencyPlugin = byName.get(dependency);
                if (dependencyPlugin == null) {
                    diagnostics.error("PLUGIN_DEPENDENCY_MISSING", "Unknown plugin dependency " + dependency, "/plugins/" + plugin.manifest().name());
                    continue;
                }
                if (dependencyPlugin.manifest().executionPhase() != plugin.manifest().executionPhase()) {
                    continue;
                }
                if (edges.get(dependency).add(plugin.manifest().name())) {
                    indegree.compute(plugin.manifest().name(), (unused, value) -> value == null ? 1 : value + 1);
                }
            }
        }
        ArrayDeque<String> queue = indegree.entrySet().stream()
                .filter(entry -> entry.getValue() == 0)
                .map(Map.Entry::getKey)
                .sorted()
                .collect(ArrayDeque::new, ArrayDeque::add, ArrayDeque::addAll);
        List<RegisteredPlugin> ordered = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            ordered.add(byName.get(current));
            List<String> nextNodes = new ArrayList<>(edges.getOrDefault(current, Set.of()));
            nextNodes.sort(String::compareTo);
            for (String next : nextNodes) {
                int nextDegree = indegree.compute(next, (unused, value) -> value == null ? 0 : value - 1);
                if (nextDegree == 0) {
                    queue.add(next);
                }
            }
            queue = queue.stream().sorted().collect(ArrayDeque::new, ArrayDeque::add, ArrayDeque::addAll);
        }
        if (ordered.size() != plugins.size()) {
            diagnostics.error("PLUGIN_CYCLE", "Detected dependency cycle in plugins", "/plugins");
        }
        return ordered;
    }

    private void materialize(Path targetRoot, List<RegisteredPlugin> ordered, List<GeneratedFile> generatedFiles) {
        ordered.stream()
                .flatMap(plugin -> plugin.manifest().ownsOutputRoots().stream())
                .distinct()
                .forEach(root -> deleteRoot(targetRoot.resolve(root)));
        for (GeneratedFile generatedFile : generatedFiles) {
            Path output = targetRoot.resolve(generatedFile.relativePath());
            try {
                Files.createDirectories(output.getParent());
                Files.writeString(output, generatedFile.content());
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to write generated file " + output, exception);
            }
        }
    }

    private void deleteRoot(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new IllegalStateException("Failed to clean output root " + root, exception);
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to clean output root " + root, exception);
        }
    }

    public record RegisteredPlugin(PluginManifest manifest, CompilerPlugin plugin) {
    }
}
