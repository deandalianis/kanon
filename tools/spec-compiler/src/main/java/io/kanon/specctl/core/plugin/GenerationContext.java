package io.kanon.specctl.core.plugin;

import io.kanon.specctl.core.diagnostics.Diagnostics;
import io.kanon.specctl.ir.CanonicalIr;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class GenerationContext {
    private final CanonicalIr ir;
    private final PluginManifest manifest;
    private final Diagnostics diagnostics;
    private final List<GeneratedFile> generatedFiles = new ArrayList<>();

    public GenerationContext(CanonicalIr ir, PluginManifest manifest, Diagnostics diagnostics) {
        this.ir = ir;
        this.manifest = manifest;
        this.diagnostics = diagnostics;
    }

    public CanonicalIr ir() {
        return ir;
    }

    public Diagnostics diagnostics() {
        return diagnostics;
    }

    public PluginManifest manifest() {
        return manifest;
    }

    public void writeFile(String ownedRoot, String relativePath, String content) {
        if (!manifest.ownsOutputRoots().contains(ownedRoot)) {
            diagnostics.error("PLUGIN_ROOT_VIOLATION", "Plugin attempted to write outside owned roots: " + ownedRoot,
                    "/" + manifest.name());
            return;
        }
        if (!ownedRoot.startsWith("src/generated/")) {
            diagnostics.error("PLUGIN_ROOT_INVALID", "Generated output root must stay under src/generated/**",
                    "/" + manifest.name());
            return;
        }
        if (relativePath.startsWith("..")) {
            diagnostics.error("PLUGIN_PATH_TRAVERSAL", "Relative path escapes owned root", "/" + manifest.name());
            return;
        }
        Path fullPath = Path.of(ownedRoot).resolve(relativePath).normalize();
        if (fullPath.toString().contains("src\\main") || fullPath.toString().contains("src/main")) {
            diagnostics.error("PLUGIN_MAIN_MUTATION", "Generated code must never target src/main/**",
                    "/" + manifest.name());
            return;
        }
        generatedFiles.add(new GeneratedFile(manifest.name(), fullPath, content));
    }

    public List<GeneratedFile> generatedFiles() {
        return List.copyOf(generatedFiles);
    }
}
