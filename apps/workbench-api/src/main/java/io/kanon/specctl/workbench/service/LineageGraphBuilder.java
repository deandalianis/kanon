package io.kanon.specctl.workbench.service;

import io.kanon.specctl.core.platform.PlatformTypes;
import io.kanon.specctl.extraction.evidence.EvidenceSnapshot;
import io.kanon.specctl.semantic.SemanticSpecDocument;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LineageGraphBuilder {
    PlatformTypes.GraphView build(SemanticSpecDocument spec, EvidenceSnapshot evidenceSnapshot) {
        LinkedHashMap<String, NodeDraft> nodes = new LinkedHashMap<>();
        LinkedHashMap<String, PlatformTypes.GraphEdge> edges = new LinkedHashMap<>();
        Map<String, MutableStats> statsById = new HashMap<>();
        Map<String, String> parentById = new HashMap<>();

        int interfaceCount = spec.interfaces().size();
        int operationCount = spec.interfaces().stream().mapToInt(item -> item.operations().size()).sum();
        int dataStoreCount = spec.dataStores().size();
        int integrationCount = spec.integrations().size();
        int scenarioCount = spec.scenarios().size();

        addNode(nodes, statsById, parentById, new NodeDraft(
                spec.service().id(),
                spec.service().name(),
                "service",
                spec.service().basePackage(),
                null,
                new MutableStats(interfaceCount, dataStoreCount, operationCount, scenarioCount, integrationCount),
                Map.of("summary", spec.service().summary())
        ));

        for (SemanticSpecDocument.InterfacePoint item : spec.interfaces()) {
            addNode(nodes, statsById, parentById, new NodeDraft(
                    item.id(),
                    item.name(),
                    "interface",
                    item.location(),
                    spec.service().id(),
                    new MutableStats(0, 0, item.operations().size(), 0, 0),
                    Map.of("protocol", item.protocol(), "kind", item.kind())
            ));
            addEdge(edges, spec.service().id(), item.id(), "DECLARES");
            rollupEvidence(item.id(), item.evidence().size(), statsById, parentById);
            for (SemanticSpecDocument.Operation operation : item.operations()) {
                addNode(nodes, statsById, parentById, new NodeDraft(
                        operation.id(),
                        operation.name(),
                        "operation",
                        operation.pathOrChannel(),
                        item.id(),
                        new MutableStats(),
                        Map.of("method", operation.method(), "summary", operation.summary())
                ));
                addEdge(edges, item.id(), operation.id(), "DECLARES");
                rollupEvidence(operation.id(), operation.evidence().size(), statsById, parentById);
            }
        }

        for (SemanticSpecDocument.DataStore item : spec.dataStores()) {
            addNode(nodes, statsById, parentById, new NodeDraft(
                    item.id(),
                    item.name(),
                    "datastore",
                    item.technology(),
                    spec.service().id(),
                    new MutableStats(),
                    Map.of("artifacts", String.join(", ", item.artifacts()))
            ));
            addEdge(edges, spec.service().id(), item.id(), "USES");
            rollupEvidence(item.id(), item.evidence().size(), statsById, parentById);
        }

        for (SemanticSpecDocument.Integration item : spec.integrations()) {
            addNode(nodes, statsById, parentById, new NodeDraft(
                    item.id(),
                    item.name(),
                    "integration",
                    item.target(),
                    spec.service().id(),
                    new MutableStats(),
                    Map.of("direction", item.direction(), "kind", item.kind())
            ));
            addEdge(edges, spec.service().id(), item.id(), "INTEGRATES_WITH");
            rollupEvidence(item.id(), item.evidence().size(), statsById, parentById);
        }

        for (SemanticSpecDocument.Workflow item : spec.workflows()) {
            addNode(nodes, statsById, parentById, new NodeDraft(
                    item.id(),
                    item.name(),
                    "workflow",
                    item.name(),
                    spec.service().id(),
                    new MutableStats(),
                    Map.of("summary", item.summary())
            ));
            addEdge(edges, spec.service().id(), item.id(), "RUNS");
            rollupEvidence(item.id(), item.evidence().size(), statsById, parentById);
        }

        for (SemanticSpecDocument.Rule item : spec.rules()) {
            addNode(nodes, statsById, parentById, new NodeDraft(
                    item.id(),
                    item.name(),
                    "rule",
                    item.category(),
                    spec.service().id(),
                    new MutableStats(),
                    Map.of("statement", item.statement())
            ));
            addEdge(edges, spec.service().id(), item.id(), "CONSTRAINS");
            rollupEvidence(item.id(), item.evidence().size(), statsById, parentById);
        }

        for (SemanticSpecDocument.Scenario item : spec.scenarios()) {
            addNode(nodes, statsById, parentById, new NodeDraft(
                    item.id(),
                    item.name(),
                    "scenario",
                    item.name(),
                    spec.service().id(),
                    new MutableStats(),
                    Map.of("then", String.join(" ", item.then()))
            ));
            addEdge(edges, spec.service().id(), item.id(), "DESCRIBES");
            rollupEvidence(item.id(), item.evidence().size(), statsById, parentById);
        }

        for (SemanticSpecDocument.SemanticNote item : spec.notes()) {
            addNode(nodes, statsById, parentById, new NodeDraft(
                    item.id(),
                    item.title(),
                    "note",
                    item.kind(),
                    spec.service().id(),
                    new MutableStats(),
                    Map.of("detail", item.detail())
            ));
            addEdge(edges, spec.service().id(), item.id(), "NOTES");
            rollupEvidence(item.id(), item.evidence().size(), statsById, parentById);
        }

        for (SemanticSpecDocument.EvidenceCitation citation : spec.service().evidence()) {
            if (citation.evidenceNodeId() != null && evidenceSnapshot.nodes().stream()
                    .noneMatch(node -> node.id().equals(citation.evidenceNodeId()))) {
                rollupConflict(spec.service().id(), true, statsById, parentById);
            }
        }

        List<PlatformTypes.GraphNode> graphNodes = nodes.values().stream()
                .map(node -> new PlatformTypes.GraphNode(
                        node.id(),
                        node.label(),
                        node.type(),
                        node.path(),
                        node.parentId(),
                        statsById.get(node.id()).freeze(),
                        node.metadata()
                ))
                .toList();
        return new PlatformTypes.GraphView(graphNodes, List.copyOf(edges.values()));
    }

    private void addNode(
            Map<String, NodeDraft> nodes,
            Map<String, MutableStats> statsById,
            Map<String, String> parentById,
            NodeDraft node
    ) {
        nodes.put(node.id(), node);
        statsById.put(node.id(), node.baseStats());
        parentById.put(node.id(), node.parentId());
    }

    private void addEdge(Map<String, PlatformTypes.GraphEdge> edges, String sourceId, String targetId, String label) {
        String id = sourceId + "->" + targetId + ":" + label;
        edges.put(id, new PlatformTypes.GraphEdge(id, sourceId, targetId, label));
    }

    private void rollupEvidence(String nodeId, int count, Map<String, MutableStats> statsById, Map<String, String> parentById) {
        String currentId = nodeId;
        while (currentId != null) {
            MutableStats stats = statsById.get(currentId);
            if (stats != null) {
                stats.evidenceCount += count;
            }
            currentId = parentById.get(currentId);
        }
    }

    private void rollupConflict(String nodeId, boolean fatal, Map<String, MutableStats> statsById, Map<String, String> parentById) {
        String currentId = nodeId;
        while (currentId != null) {
            MutableStats stats = statsById.get(currentId);
            if (stats != null) {
                if (fatal) {
                    stats.blockingConflictCount++;
                } else {
                    stats.warningConflictCount++;
                }
            }
            currentId = parentById.get(currentId);
        }
    }

    private record NodeDraft(
            String id,
            String label,
            String type,
            String path,
            String parentId,
            MutableStats baseStats,
            Map<String, Object> metadata
    ) {
    }

    private static final class MutableStats {
        private int evidenceCount;
        private int warningConflictCount;
        private int blockingConflictCount;
        private int boundedContextCount;
        private int aggregateCount;
        private int commandCount;
        private int entityCount;
        private int eventCount;

        private MutableStats() {
        }

        private MutableStats(int boundedContextCount, int aggregateCount, int commandCount, int entityCount,
                             int eventCount) {
            this.boundedContextCount = boundedContextCount;
            this.aggregateCount = aggregateCount;
            this.commandCount = commandCount;
            this.entityCount = entityCount;
            this.eventCount = eventCount;
        }

        private PlatformTypes.GraphNodeStats freeze() {
            return new PlatformTypes.GraphNodeStats(
                    evidenceCount,
                    warningConflictCount,
                    blockingConflictCount,
                    boundedContextCount,
                    aggregateCount,
                    commandCount,
                    entityCount,
                    eventCount
            );
        }
    }
}
