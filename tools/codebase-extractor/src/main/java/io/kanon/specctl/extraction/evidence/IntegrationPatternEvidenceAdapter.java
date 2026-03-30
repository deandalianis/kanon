package io.kanon.specctl.extraction.evidence;

import io.kanon.specctl.extraction.evidence.EvidenceEdge;
import io.kanon.specctl.extraction.evidence.EvidenceNode;
import io.kanon.specctl.extraction.evidence.EvidenceRef;
import io.kanon.specctl.extraction.ir.CodebaseIr;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class IntegrationPatternEvidenceAdapter implements EvidenceAdapter {
    private static final List<String> KEYWORDS =
            List.of("oauth2", "sftp", "outbox", "inbox", "debezium", "kafka", "webclient", "restclient", "cxf");

    @Override
    public String name() {
        return "integration-patterns";
    }

    @Override
    public EvidenceAdapterResult collect(EvidenceAdapterContext context) {
        List<EvidenceNode> nodes = new ArrayList<>();
        List<EvidenceEdge> edges = new ArrayList<>();
        List<EvidenceRef> refs = new ArrayList<>();

        for (CodebaseIr.Type type : context.codebaseIr().types()) {
            String haystack = (type.qualifiedName() + " " + type.simpleName()).toLowerCase(Locale.ROOT);
            for (String keyword : KEYWORDS) {
                if (!haystack.contains(keyword)) {
                    continue;
                }
                EvidenceNode node = new EvidenceNode(
                        EvidenceSupport.stableId("integration-artifact", keyword, type.id()),
                        "integration-artifact",
                        type.simpleName(),
                        type.qualifiedName(),
                        Map.of("keyword", keyword, "qualifiedName", type.qualifiedName())
                );
                nodes.add(node);
                edges.add(EvidenceSupport.edge("BACKED_BY", node.id(), type.id()));
                type.provenance().stream().findFirst().ifPresent(provenance -> {
                    Path file = Path.of(provenance.file());
                    refs.add(new EvidenceRef(node.id(), EvidenceSupport.fileNodeId(file), provenance.file(),
                            provenance.startLine(), provenance.endLine(),
                            EvidenceSupport.excerpt(file, provenance.startLine(), provenance.endLine())));
                });
            }
        }

        for (Path file : context.resourceFiles()) {
            try {
                String content = Files.readString(file).toLowerCase(Locale.ROOT);
                for (String keyword : KEYWORDS) {
                    if (!content.contains(keyword)) {
                        continue;
                    }
                    EvidenceNode node = new EvidenceNode(
                            EvidenceSupport.stableId("integration-config", keyword, file.toString()),
                            "integration-config",
                            file.getFileName().toString(),
                            file.toString(),
                            Map.of("keyword", keyword, "file", file.toString())
                    );
                    nodes.add(node);
                    refs.addAll(EvidenceSupport.fileBoundRefs(node.id(), file));
                }
            } catch (IOException ignored) {
            }
        }

        return new EvidenceAdapterResult(nodes, edges, refs, List.of());
    }
}
