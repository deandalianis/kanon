package io.kanon.specctl.core.plugin;

import java.nio.file.Path;

public record GeneratedFile(String pluginName, Path relativePath, String content) {
}
