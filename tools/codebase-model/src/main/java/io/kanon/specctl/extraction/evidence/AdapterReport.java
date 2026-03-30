package io.kanon.specctl.extraction.evidence;

import java.util.List;

public record AdapterReport(
        String adapter,
        String status,
        int nodeCount,
        int edgeCount,
        List<String> diagnostics
) {
    public AdapterReport {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
