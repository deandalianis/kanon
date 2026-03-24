package io.kanon.specctl.workbench.service;

import io.kanon.specctl.core.extract.ExtractionResult;
import io.kanon.specctl.core.platform.PlatformTypes;
import io.kanon.specctl.ir.CanonicalIr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LineageGraphBuilder {
    PlatformTypes.GraphView build(CanonicalIr ir, ExtractionResult extractionResult) {
        LinkedHashMap<String, NodeDraft> nodesById = new LinkedHashMap<>();
        Map<String, MutableStats> statsById = new HashMap<>();
        Map<String, String> parentById = new HashMap<>();
        List<PlatformTypes.GraphEdge> edges = new ArrayList<>();

        int aggregateCount = ir.boundedContexts().stream().mapToInt(context -> context.aggregates().size()).sum();
        int commandCount = ir.boundedContexts().stream()
                .flatMap(context -> context.aggregates().stream())
                .mapToInt(aggregate -> aggregate.commands().size())
                .sum();
        int entityCount = ir.boundedContexts().stream()
                .flatMap(context -> context.aggregates().stream())
                .mapToInt(aggregate -> aggregate.entities().size())
                .sum();
        int eventCount = ir.boundedContexts().stream()
                .flatMap(context -> context.aggregates().stream())
                .mapToInt(aggregate -> aggregate.events().size())
                .sum();

        addNode(
                nodesById,
                statsById,
                parentById,
                new NodeDraft(
                        ir.service().stableId(),
                        ir.service().name(),
                        "service",
                        ir.service().canonicalPath(),
                        null,
                        new MutableStats(ir.boundedContexts().size(), aggregateCount, commandCount, entityCount, eventCount),
                        Map.of("basePackage", ir.service().basePackage())
                )
        );

        for (CanonicalIr.BoundedContext boundedContext : ir.boundedContexts()) {
            int contextCommandCount = boundedContext.aggregates().stream().mapToInt(aggregate -> aggregate.commands().size()).sum();
            int contextEntityCount = boundedContext.aggregates().stream().mapToInt(aggregate -> aggregate.entities().size()).sum();
            int contextEventCount = boundedContext.aggregates().stream().mapToInt(aggregate -> aggregate.events().size()).sum();
            addNode(
                    nodesById,
                    statsById,
                    parentById,
                    new NodeDraft(
                            boundedContext.stableId(),
                            boundedContext.name(),
                            "bounded-context",
                            boundedContext.canonicalPath(),
                            ir.service().stableId(),
                            new MutableStats(0, boundedContext.aggregates().size(), contextCommandCount, contextEntityCount, contextEventCount),
                            Map.of()
                    )
            );
            edges.add(new PlatformTypes.GraphEdge(
                    ir.service().stableId() + "->" + boundedContext.stableId(),
                    ir.service().stableId(),
                    boundedContext.stableId(),
                    "DECLARES"
            ));

            for (CanonicalIr.Aggregate aggregate : boundedContext.aggregates()) {
                addNode(
                        nodesById,
                        statsById,
                        parentById,
                        new NodeDraft(
                                aggregate.stableId(),
                                aggregate.name(),
                                "aggregate",
                                aggregate.canonicalPath(),
                                boundedContext.stableId(),
                                new MutableStats(0, 0, aggregate.commands().size(), aggregate.entities().size(), aggregate.events().size()),
                                Map.of()
                        )
                );
                edges.add(new PlatformTypes.GraphEdge(
                        boundedContext.stableId() + "->" + aggregate.stableId(),
                        boundedContext.stableId(),
                        aggregate.stableId(),
                        "DECLARES"
                ));

                for (CanonicalIr.Command command : aggregate.commands()) {
                    addNode(
                            nodesById,
                            statsById,
                            parentById,
                            new NodeDraft(
                                    command.stableId(),
                                    command.name(),
                                    "command",
                                    command.canonicalPath(),
                                    aggregate.stableId(),
                                    new MutableStats(),
                                    Map.of(
                                            "method", command.http().method(),
                                            "httpPath", command.http().path()
                                    )
                            )
                    );
                    edges.add(new PlatformTypes.GraphEdge(
                            aggregate.stableId() + "->" + command.stableId(),
                            aggregate.stableId(),
                            command.stableId(),
                            "HANDLES"
                    ));
                }

                for (CanonicalIr.Entity entity : aggregate.entities()) {
                    addNode(
                            nodesById,
                            statsById,
                            parentById,
                            new NodeDraft(
                                    entity.stableId(),
                                    entity.name(),
                                    "entity",
                                    entity.canonicalPath(),
                                    aggregate.stableId(),
                                    new MutableStats(),
                                    Map.of(
                                            "table", entity.table(),
                                            "fieldCount", entity.fields().size()
                                    )
                            )
                    );
                    edges.add(new PlatformTypes.GraphEdge(
                            aggregate.stableId() + "->" + entity.stableId(),
                            aggregate.stableId(),
                            entity.stableId(),
                            "PERSISTS"
                    ));
                }

                for (CanonicalIr.Event event : aggregate.events()) {
                    addNode(
                            nodesById,
                            statsById,
                            parentById,
                            new NodeDraft(
                                    event.stableId(),
                                    event.name(),
                                    "event",
                                    event.canonicalPath(),
                                    aggregate.stableId(),
                                    new MutableStats(),
                                    Map.of("topic", event.topic())
                            )
                    );
                    edges.add(new PlatformTypes.GraphEdge(
                            aggregate.stableId() + "->" + event.stableId(),
                            aggregate.stableId(),
                            event.stableId(),
                            "EMITS"
                    ));
                }
            }
        }

        Map<String, String> nodeIdByPath = new HashMap<>();
        List<PathNode> pathNodes = nodesById.values().stream()
                .map(node -> new PathNode(node.id(), node.path()))
                .sorted(Comparator.comparingInt((PathNode node) -> node.path().length()).reversed())
                .toList();
        for (NodeDraft node : nodesById.values()) {
            nodeIdByPath.put(node.path(), node.id());
        }

        for (ExtractionResult.Provenance provenance : extractionResult.provenance()) {
            String ownerId = resolveOwnerId(provenance.path(), ir.service().stableId(), nodeIdByPath, pathNodes);
            rollupEvidence(ownerId, statsById, parentById);
        }

        for (ExtractionResult.Conflict conflict : extractionResult.conflicts()) {
            String ownerId = resolveOwnerId(conflict.path(), ir.service().stableId(), nodeIdByPath, pathNodes);
            rollupConflict(ownerId, conflict.fatal(), statsById, parentById);
        }

        List<PlatformTypes.GraphNode> nodes = nodesById.values().stream()
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

        return new PlatformTypes.GraphView(nodes, edges);
    }

    private void addNode(
            LinkedHashMap<String, NodeDraft> nodesById,
            Map<String, MutableStats> statsById,
            Map<String, String> parentById,
            NodeDraft node
    ) {
        nodesById.put(node.id(), node);
        statsById.put(node.id(), node.baseStats());
        parentById.put(node.id(), node.parentId());
    }

    private void rollupEvidence(String nodeId, Map<String, MutableStats> statsById, Map<String, String> parentById) {
        rollup(nodeId, statsById, parentById, true);
    }

    private void rollupConflict(String nodeId, boolean fatal, Map<String, MutableStats> statsById, Map<String, String> parentById) {
        rollup(nodeId, statsById, parentById, false, fatal);
    }

    private void rollup(String nodeId, Map<String, MutableStats> statsById, Map<String, String> parentById, boolean evidenceOnly) {
        rollup(nodeId, statsById, parentById, evidenceOnly, false);
    }

    private void rollup(
            String nodeId,
            Map<String, MutableStats> statsById,
            Map<String, String> parentById,
            boolean evidenceOnly,
            boolean fatalConflict
    ) {
        String currentId = nodeId;
        while (currentId != null) {
            MutableStats stats = statsById.get(currentId);
            if (stats != null) {
                if (evidenceOnly) {
                    stats.evidenceCount++;
                } else if (fatalConflict) {
                    stats.blockingConflictCount++;
                } else {
                    stats.warningConflictCount++;
                }
            }
            currentId = parentById.get(currentId);
        }
    }

    private String resolveOwnerId(
            String artifactPath,
            String serviceId,
            Map<String, String> nodeIdByPath,
            List<PathNode> pathNodes
    ) {
        if (artifactPath == null || artifactPath.isBlank()) {
            return serviceId;
        }

        String exactMatch = nodeIdByPath.get(artifactPath);
        if (exactMatch != null) {
            return exactMatch;
        }

        for (PathNode node : pathNodes) {
            if (matchesPathPrefix(artifactPath, node.path())) {
                return node.id();
            }
        }
        return serviceId;
    }

    private boolean matchesPathPrefix(String candidate, String nodePath) {
        if (candidate.equals(nodePath)) {
            return true;
        }

        if (!candidate.startsWith(nodePath)) {
            return false;
        }

        if (candidate.length() == nodePath.length()) {
            return true;
        }

        char separator = candidate.charAt(nodePath.length());
        return separator == '/' || separator == ':' || separator == '#';
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

    private record PathNode(String id, String path) {
    }

    static final class MutableStats {
        private int evidenceCount;
        private int warningConflictCount;
        private int blockingConflictCount;
        private final int boundedContextCount;
        private final int aggregateCount;
        private final int commandCount;
        private final int entityCount;
        private final int eventCount;

        MutableStats() {
            this(0, 0, 0, 0, 0);
        }

        MutableStats(
                int boundedContextCount,
                int aggregateCount,
                int commandCount,
                int entityCount,
                int eventCount
        ) {
            this.boundedContextCount = boundedContextCount;
            this.aggregateCount = aggregateCount;
            this.commandCount = commandCount;
            this.entityCount = entityCount;
            this.eventCount = eventCount;
        }

        PlatformTypes.GraphNodeStats freeze() {
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
