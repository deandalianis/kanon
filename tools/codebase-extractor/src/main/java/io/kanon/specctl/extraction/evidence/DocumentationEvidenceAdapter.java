package io.kanon.specctl.extraction.evidence;

import io.kanon.specctl.extraction.evidence.EvidenceEdge;
import io.kanon.specctl.extraction.evidence.EvidenceNode;
import io.kanon.specctl.extraction.evidence.EvidenceRef;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DocumentationEvidenceAdapter implements EvidenceAdapter {
    @Override
    public String name() {
        return "documentation";
    }

    @Override
    public EvidenceAdapterResult collect(EvidenceAdapterContext context) {
        List<EvidenceNode> nodes = new ArrayList<>();
        List<EvidenceEdge> edges = new ArrayList<>();
        List<EvidenceRef> refs = new ArrayList<>();
        for (Path file : context.resourceFiles()) {
            if (!file.getFileName().toString().toLowerCase().endsWith(".md")) {
                continue;
            }
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("file", file.toString());
            EvidenceNode fileNode = new EvidenceNode(
                    EvidenceSupport.stableId("doc-file", file.toString()),
                    "documentation-file",
                    file.getFileName().toString(),
                    file.toString(),
                    attributes
            );
            nodes.add(fileNode);
            refs.addAll(EvidenceSupport.fileBoundRefs(fileNode.id(), file));
            for (EvidenceNode section : EvidenceSupport.markdownSectionNodes(file)) {
                nodes.add(section);
                edges.add(EvidenceSupport.edge("CONTAINS", fileNode.id(), section.id()));
                refs.add(new EvidenceRef(section.id(), fileNode.id(), file.toString(),
                        Integer.parseInt(section.attributes().getOrDefault("line", "1")),
                        Integer.parseInt(section.attributes().getOrDefault("line", "1")),
                        ""));
            }
        }
        return new EvidenceAdapterResult(nodes, edges, refs, List.of());
    }
}
