package io.kanon.specctl.extraction.evidence;

import io.kanon.specctl.extraction.evidence.EvidenceEdge;
import io.kanon.specctl.extraction.evidence.EvidenceNode;
import io.kanon.specctl.extraction.evidence.EvidenceRef;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DatabaseMigrationEvidenceAdapter implements EvidenceAdapter {
    @Override
    public String name() {
        return "migrations";
    }

    @Override
    public EvidenceAdapterResult collect(EvidenceAdapterContext context) {
        List<EvidenceNode> nodes = new ArrayList<>();
        List<EvidenceEdge> edges = new ArrayList<>();
        List<EvidenceRef> refs = new ArrayList<>();
        for (Path file : context.resourceFiles()) {
            String lower = file.toString().toLowerCase();
            if (!EvidenceSupport.hasAnySegment(file, "/db/changelog/", "\\db\\changelog\\", "/db/migration/",
                    "\\db\\migration\\", "liquibase", "flyway")) {
                continue;
            }
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("tool", lower.contains("changelog") || lower.contains("liquibase") ? "liquibase" : "flyway");
            EvidenceNode migration = new EvidenceNode(
                    EvidenceSupport.stableId("migration", file.toString()),
                    "database-migration",
                    file.getFileName().toString(),
                    file.toString(),
                    attributes
            );
            nodes.add(migration);
            refs.addAll(EvidenceSupport.fileBoundRefs(migration.id(), file));
            if (lower.contains("master.") || lower.contains("changelog")) {
                EvidenceNode collection = new EvidenceNode(
                        EvidenceSupport.stableId("migration-collection", file.getParent().toString()),
                        "migration-collection",
                        file.getParent().getFileName().toString(),
                        file.getParent().toString(),
                        Map.of("directory", file.getParent().toString())
                );
                nodes.add(collection);
                edges.add(EvidenceSupport.edge("CONTAINS", collection.id(), migration.id()));
            }
        }
        return new EvidenceAdapterResult(nodes, edges, refs, List.of());
    }
}
