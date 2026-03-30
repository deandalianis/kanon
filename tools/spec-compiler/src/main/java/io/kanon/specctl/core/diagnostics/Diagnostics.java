package io.kanon.specctl.core.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Diagnostics {
    private final List<Diagnostic> entries = new ArrayList<>();

    public void error(String code, String message, String path) {
        entries.add(new Diagnostic(DiagnosticLevel.ERROR, code, message, path));
    }

    public void warn(String code, String message, String path) {
        entries.add(new Diagnostic(DiagnosticLevel.WARN, code, message, path));
    }

    public boolean hasErrors() {
        return entries.stream().anyMatch(entry -> entry.level() == DiagnosticLevel.ERROR);
    }

    public List<Diagnostic> entries() {
        return Collections.unmodifiableList(entries);
    }

    public void throwIfErrors() {
        if (hasErrors()) {
            throw new CompilationException(entries());
        }
    }

    public enum DiagnosticLevel {
        ERROR,
        WARN
    }

    public record Diagnostic(DiagnosticLevel level, String code, String message, String path) {
    }
}
