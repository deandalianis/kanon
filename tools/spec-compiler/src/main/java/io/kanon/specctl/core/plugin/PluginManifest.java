package io.kanon.specctl.core.plugin;

import java.util.List;

public record PluginManifest(
        String name,
        String version,
        List<String> dependencies,
        ExecutionPhase executionPhase,
        List<String> ownsOutputRoots
) {
    public PluginManifest {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        ownsOutputRoots = ownsOutputRoots == null ? List.of() : List.copyOf(ownsOutputRoots);
    }
}
