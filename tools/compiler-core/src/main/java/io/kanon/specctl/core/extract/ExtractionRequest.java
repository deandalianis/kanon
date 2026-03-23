package io.kanon.specctl.core.extract;

import java.nio.file.Path;

public record ExtractionRequest(Path sourceRoot, boolean includeExternalDependencies) {
}
