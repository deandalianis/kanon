package io.kanon.specctl.extraction.ir;

import java.util.List;

public record BuildResolution(
        BuildTool buildTool,
        String projectRoot,
        String buildFile,
        String rootModule,
        List<String> buildCommand,
        List<ResolvedModule> modules,
        List<String> sourceRoots,
        List<String> generatedSourceRoots,
        List<String> compileClasspath,
        List<String> runtimeClasspath,
        List<String> classOutputDirectories,
        List<String> resourceOutputDirectories,
        String javaRelease,
        String mainClass,
        ProjectCapabilities capabilities,
        boolean buildSucceeded,
        List<String> diagnostics
) {
    public BuildResolution {
        buildTool = buildTool == null ? BuildTool.UNKNOWN : buildTool;
        buildCommand = immutable(buildCommand);
        modules = modules == null ? List.of() : List.copyOf(modules);
        sourceRoots = immutable(sourceRoots);
        generatedSourceRoots = immutable(generatedSourceRoots);
        compileClasspath = immutable(compileClasspath);
        runtimeClasspath = immutable(runtimeClasspath);
        classOutputDirectories = immutable(classOutputDirectories);
        resourceOutputDirectories = immutable(resourceOutputDirectories);
        javaRelease = javaRelease == null || javaRelease.isBlank() ? "21" : javaRelease;
        capabilities = capabilities == null ? ProjectCapabilities.minimal() : capabilities;
        diagnostics = immutable(diagnostics);
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    public record ResolvedModule(
            String path,
            String projectDir,
            String buildFile,
            List<String> sourceRoots,
            List<String> generatedSourceRoots,
            List<String> compileClasspath,
            List<String> runtimeClasspath,
            List<String> classOutputDirectories,
            List<String> resourceOutputDirectories
    ) {
        public ResolvedModule {
            sourceRoots = sourceRoots == null ? List.of() : List.copyOf(sourceRoots);
            generatedSourceRoots = generatedSourceRoots == null ? List.of() : List.copyOf(generatedSourceRoots);
            compileClasspath = compileClasspath == null ? List.of() : List.copyOf(compileClasspath);
            runtimeClasspath = runtimeClasspath == null ? List.of() : List.copyOf(runtimeClasspath);
            classOutputDirectories = classOutputDirectories == null ? List.of() : List.copyOf(classOutputDirectories);
            resourceOutputDirectories =
                    resourceOutputDirectories == null ? List.of() : List.copyOf(resourceOutputDirectories);
        }
    }
}
