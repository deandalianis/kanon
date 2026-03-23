package io.kanon.specctl.core;

import io.kanon.specctl.core.diagnostics.Diagnostics;
import io.kanon.specctl.core.normalize.SpecNormalizer;
import io.kanon.specctl.core.plugin.GeneratedFile;
import io.kanon.specctl.core.plugin.PluginRuntime;
import io.kanon.specctl.core.plugin.PluginRuntime.RegisteredPlugin;
import io.kanon.specctl.dsl.SpecDocument;
import io.kanon.specctl.dsl.SpecLoader;
import io.kanon.specctl.ir.CanonicalIr;

import java.nio.file.Path;
import java.util.List;

public final class SpecCompiler {
    private final SpecLoader specLoader = new SpecLoader();
    private final SpecNormalizer normalizer = new SpecNormalizer();
    private final PluginRuntime pluginRuntime = new PluginRuntime();

    public CompilationArtifact compile(Path specPath) {
        Diagnostics diagnostics = new Diagnostics();
        SpecDocument spec = specLoader.loadSpec(specPath);
        return compile(spec, diagnostics);
    }

    public List<GeneratedFile> generate(Path specPath, Path targetRoot, List<RegisteredPlugin> plugins) {
        Diagnostics diagnostics = new Diagnostics();
        SpecDocument spec = specLoader.loadSpec(specPath);
        return generate(spec, targetRoot, plugins, diagnostics);
    }

    public CompilationArtifact compile(SpecDocument spec) {
        Diagnostics diagnostics = new Diagnostics();
        return compile(spec, diagnostics);
    }

    public List<GeneratedFile> generate(SpecDocument spec, Path targetRoot, List<RegisteredPlugin> plugins) {
        Diagnostics diagnostics = new Diagnostics();
        return generate(spec, targetRoot, plugins, diagnostics);
    }

    private CompilationArtifact compile(SpecDocument spec, Diagnostics diagnostics) {
        CanonicalIr ir = normalizer.normalize(spec, diagnostics);
        return new CompilationArtifact(spec, ir, diagnostics.entries());
    }

    private List<GeneratedFile> generate(SpecDocument spec, Path targetRoot, List<RegisteredPlugin> plugins, Diagnostics diagnostics) {
        CanonicalIr ir = normalizer.normalize(spec, diagnostics);
        diagnostics.throwIfErrors();
        return pluginRuntime.generate(ir, targetRoot, plugins, diagnostics);
    }

    public record CompilationArtifact(SpecDocument source, CanonicalIr canonicalIr, List<Diagnostics.Diagnostic> diagnostics) {
    }
}
