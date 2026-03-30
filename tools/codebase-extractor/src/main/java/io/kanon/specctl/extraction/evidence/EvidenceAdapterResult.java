package io.kanon.specctl.extraction.evidence;

import io.kanon.specctl.extraction.evidence.AdapterReport;
import io.kanon.specctl.extraction.evidence.EvidenceEdge;
import io.kanon.specctl.extraction.evidence.EvidenceNode;
import io.kanon.specctl.extraction.evidence.EvidenceRef;
import java.util.List;

public record EvidenceAdapterResult(
        List<EvidenceNode> nodes,
        List<EvidenceEdge> edges,
        List<EvidenceRef> refs,
        List<String> diagnostics
) {
    public EvidenceAdapterResult {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        refs = refs == null ? List.of() : List.copyOf(refs);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public AdapterReport toReport(String adapterName) {
        return new AdapterReport(
                adapterName,
                nodes.isEmpty() && edges.isEmpty() ? "SKIPPED" : "CONFIRMED",
                nodes.size(),
                edges.size(),
                diagnostics
        );
    }
}
