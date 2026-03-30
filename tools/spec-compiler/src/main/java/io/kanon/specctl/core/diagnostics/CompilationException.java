package io.kanon.specctl.core.diagnostics;

import java.util.List;

public final class CompilationException extends RuntimeException {
    private final List<Diagnostics.Diagnostic> diagnostics;

    public CompilationException(List<Diagnostics.Diagnostic> diagnostics) {
        super("Compilation failed with " + diagnostics.size() + " diagnostic(s): " + diagnostics);
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<Diagnostics.Diagnostic> diagnostics() {
        return diagnostics;
    }
}
