package io.kanon.specctl.core.semantic;

import io.kanon.specctl.extraction.evidence.EvidenceNode;
import io.kanon.specctl.extraction.evidence.EvidenceRef;
import io.kanon.specctl.extraction.evidence.EvidenceSnapshot;
import io.kanon.specctl.semantic.SemanticSpecDocument;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public final class SemanticSpecSynthesisService {
    public SemanticSpecDocument synthesize(String serviceName, String basePackage, EvidenceSnapshot evidenceSnapshot) {
        Map<String, EvidenceNode> nodesById = evidenceSnapshot.nodes().stream()
                .collect(Collectors.toMap(EvidenceNode::id, node -> node, (left, right) -> left, LinkedHashMap::new));
        Map<String, List<EvidenceRef>> refsByOwner = evidenceSnapshot.refs().stream()
                .collect(Collectors.groupingBy(EvidenceRef::ownerId, LinkedHashMap::new, Collectors.toList()));

        String resolvedServiceName = serviceName == null || serviceName.isBlank()
                ? labelOfFirst(nodesById.values(), node -> "project".equals(node.kind()), "service")
                : serviceName;
        String summary = labelOfFirst(nodesById.values(), node -> "documentation-section".equals(node.kind()),
                "Semantic draft synthesized from deterministic evidence");

        List<SemanticSpecDocument.InterfacePoint> interfaces = buildInterfaces(nodesById, refsByOwner);
        List<SemanticSpecDocument.DataStore> dataStores = buildDataStores(nodesById, refsByOwner);
        List<SemanticSpecDocument.Integration> integrations = buildIntegrations(nodesById, refsByOwner);
        List<SemanticSpecDocument.Workflow> workflows = buildWorkflows(nodesById, refsByOwner);
        List<SemanticSpecDocument.Rule> rules = buildRules(nodesById, refsByOwner);
        List<SemanticSpecDocument.Scenario> scenarios = buildScenarios(interfaces, nodesById, refsByOwner);
        List<SemanticSpecDocument.SemanticNote> notes = buildNotes(nodesById, refsByOwner);

        SemanticSpecDocument.Service service = new SemanticSpecDocument.Service(
                stableId("service", resolvedServiceName),
                resolvedServiceName,
                basePackage,
                summary,
                citationsFor(
                        nodesById.values().stream()
                                .filter(node -> "project".equals(node.kind()) || "documentation-section".equals(node.kind()))
                                .map(EvidenceNode::id)
                                .toList(),
                        refsByOwner
                ),
                new SemanticSpecDocument.Confidence("CONFIRMED", "Derived from deterministic evidence and cited docs"),
                Map.of("evidenceNodeCount", String.valueOf(evidenceSnapshot.nodes().size()))
        );

        return new SemanticSpecDocument(
                1,
                "1.0.0",
                service,
                interfaces,
                dataStores,
                integrations,
                workflows,
                rules,
                scenarios,
                notes
        );
    }

    private List<SemanticSpecDocument.InterfacePoint> buildInterfaces(
            Map<String, EvidenceNode> nodesById,
            Map<String, List<EvidenceRef>> refsByOwner
    ) {
        List<SemanticSpecDocument.InterfacePoint> result = new ArrayList<>();
        List<EvidenceNode> httpEndpoints = nodesById.values().stream()
                .filter(node -> "http-endpoint".equals(node.kind()) || "contract-operation".equals(node.kind()))
                .sorted(Comparator.comparing(EvidenceNode::label))
                .toList();
        if (!httpEndpoints.isEmpty()) {
            List<SemanticSpecDocument.Operation> operations = httpEndpoints.stream()
                    .map(node -> new SemanticSpecDocument.Operation(
                            stableId("operation", node.id()),
                            node.label(),
                            node.attributes().getOrDefault("method", ""),
                            node.attributes().getOrDefault("path", node.path()),
                            "Evidence-backed HTTP operation",
                            citationsFor(List.of(node.id()), refsByOwner),
                            new SemanticSpecDocument.Confidence("CONFIRMED", "Mapped directly from endpoint evidence")
                    ))
                    .toList();
            result.add(new SemanticSpecDocument.InterfacePoint(
                    stableId("interface", "http"),
                    "HTTP Surface",
                    "http",
                    "HTTP",
                    "/",
                    "HTTP endpoints and contract operations observed in code and contracts",
                    operations,
                    citationsFor(httpEndpoints.stream().map(EvidenceNode::id).toList(), refsByOwner),
                    new SemanticSpecDocument.Confidence("CONFIRMED", "Grouped from deterministic endpoint evidence")
            ));
        }

        List<EvidenceNode> channels = nodesById.values().stream()
                .filter(node -> "contract-channel".equals(node.kind()))
                .sorted(Comparator.comparing(EvidenceNode::label))
                .toList();
        if (!channels.isEmpty()) {
            List<SemanticSpecDocument.Operation> operations = channels.stream()
                    .map(node -> new SemanticSpecDocument.Operation(
                            stableId("operation", node.id()),
                            node.label(),
                            "PUBLISH/SUBSCRIBE",
                            node.attributes().getOrDefault("channel", node.label()),
                            "Async contract channel",
                            citationsFor(List.of(node.id()), refsByOwner),
                            new SemanticSpecDocument.Confidence("CONFIRMED", "Mapped directly from AsyncAPI evidence")
                    ))
                    .toList();
            result.add(new SemanticSpecDocument.InterfacePoint(
                    stableId("interface", "async"),
                    "Async Messaging",
                    "messaging",
                    "ASYNC",
                    "channels",
                    "Async message channels declared in contract artifacts",
                    operations,
                    citationsFor(channels.stream().map(EvidenceNode::id).toList(), refsByOwner),
                    new SemanticSpecDocument.Confidence("CONFIRMED", "Derived from contract channel evidence")
            ));
        }

        List<EvidenceNode> jobs = nodesById.values().stream()
                .filter(node -> node.kind().endsWith("job"))
                .sorted(Comparator.comparing(EvidenceNode::label))
                .toList();
        if (!jobs.isEmpty()) {
            List<SemanticSpecDocument.Operation> operations = jobs.stream()
                    .map(node -> new SemanticSpecDocument.Operation(
                            stableId("operation", node.id()),
                            node.label(),
                            "RUN",
                            node.path(),
                            "Scheduled or recurring execution unit",
                            citationsFor(List.of(node.id()), refsByOwner),
                            new SemanticSpecDocument.Confidence("CONFIRMED", "Mapped directly from scheduling evidence")
                    ))
                    .toList();
            result.add(new SemanticSpecDocument.InterfacePoint(
                    stableId("interface", "jobs"),
                    "Background Jobs",
                    "jobs",
                    "BACKGROUND",
                    "scheduler",
                    "Scheduled and recurring execution surfaces",
                    operations,
                    citationsFor(jobs.stream().map(EvidenceNode::id).toList(), refsByOwner),
                    new SemanticSpecDocument.Confidence("CONFIRMED", "Derived from scheduling evidence")
            ));
        }
        return result;
    }

    private List<SemanticSpecDocument.DataStore> buildDataStores(
            Map<String, EvidenceNode> nodesById,
            Map<String, List<EvidenceRef>> refsByOwner
    ) {
        List<EvidenceNode> persistenceEntities = nodesById.values().stream()
                .filter(node -> "persistence-entity".equals(node.kind()))
                .toList();
        List<EvidenceNode> migrations = nodesById.values().stream()
                .filter(node -> "database-migration".equals(node.kind()))
                .toList();
        if (persistenceEntities.isEmpty() && migrations.isEmpty()) {
            return List.of();
        }
        List<String> artifacts = new ArrayList<>();
        persistenceEntities.forEach(node -> artifacts.add(node.label()));
        migrations.forEach(node -> artifacts.add(node.label()));
        return List.of(new SemanticSpecDocument.DataStore(
                stableId("datastore", "primary"),
                "Primary Relational Store",
                migrations.stream().anyMatch(node -> "flyway".equalsIgnoreCase(node.attributes().get("tool")))
                        ? "flyway-relational"
                        : "liquibase-relational",
                "Relational persistence artifacts observed through entities and migration files",
                artifacts.stream().distinct().sorted().toList(),
                citationsFor(
                        persistenceEntities.stream().map(EvidenceNode::id).toList(),
                        refsByOwner,
                        migrations.stream().map(EvidenceNode::id).toList()
                ),
                new SemanticSpecDocument.Confidence("CONFIRMED",
                        "Backed by persistence entities and database migration evidence")
        ));
    }

    private List<SemanticSpecDocument.Integration> buildIntegrations(
            Map<String, EvidenceNode> nodesById,
            Map<String, List<EvidenceRef>> refsByOwner
    ) {
        Map<String, List<EvidenceNode>> byKeyword = nodesById.values().stream()
                .filter(node -> "integration-artifact".equals(node.kind()) || "integration-config".equals(node.kind()))
                .collect(Collectors.groupingBy(node -> node.attributes().getOrDefault("keyword", "integration"),
                        LinkedHashMap::new, Collectors.toList()));
        List<SemanticSpecDocument.Integration> integrations = new ArrayList<>();
        byKeyword.forEach((keyword, evidenceNodes) -> integrations.add(new SemanticSpecDocument.Integration(
                stableId("integration", keyword),
                keyword.toUpperCase(Locale.ROOT),
                keyword,
                "observed",
                keyword,
                "Integration-related artifacts detected in code or configuration",
                evidenceNodes.stream().map(EvidenceNode::label).sorted().toList(),
                citationsFor(evidenceNodes.stream().map(EvidenceNode::id).toList(), refsByOwner),
                new SemanticSpecDocument.Confidence("PARTIAL", "Artifacts are exact; integration role is subject to review")
        )));
        return integrations;
    }

    private List<SemanticSpecDocument.Workflow> buildWorkflows(
            Map<String, EvidenceNode> nodesById,
            Map<String, List<EvidenceRef>> refsByOwner
    ) {
        List<SemanticSpecDocument.Workflow> workflows = new ArrayList<>();
        nodesById.values().stream()
                .filter(node -> node.kind().endsWith("job"))
                .forEach(node -> workflows.add(new SemanticSpecDocument.Workflow(
                        stableId("workflow", node.id()),
                        node.label(),
                        "Workflow anchored on scheduled or recurring execution",
                        List.of(new SemanticSpecDocument.WorkflowStep(
                                stableId("workflow-step", node.id()),
                                "execution",
                                "Run " + node.label(),
                                citationsFor(List.of(node.id()), refsByOwner)
                        )),
                        citationsFor(List.of(node.id()), refsByOwner),
                        new SemanticSpecDocument.Confidence("CONFIRMED", "Backed by scheduling evidence")
                )));

        nodesById.values().stream()
                .filter(node -> "documentation-section".equals(node.kind()))
                .filter(node -> node.label().toLowerCase(Locale.ROOT).contains("flow")
                        || node.label().toLowerCase(Locale.ROOT).contains("sync")
                        || node.label().toLowerCase(Locale.ROOT).contains("migration"))
                .forEach(node -> workflows.add(new SemanticSpecDocument.Workflow(
                        stableId("workflow", node.id()),
                        node.label(),
                        "Workflow described in repository documentation",
                        List.of(new SemanticSpecDocument.WorkflowStep(
                                stableId("workflow-step", node.id()),
                                "documentation",
                                node.label(),
                                citationsFor(List.of(node.id()), refsByOwner)
                        )),
                        citationsFor(List.of(node.id()), refsByOwner),
                        new SemanticSpecDocument.Confidence("PARTIAL", "Backed by documentation evidence and pending synthesis review")
                )));
        return workflows;
    }

    private List<SemanticSpecDocument.Rule> buildRules(
            Map<String, EvidenceNode> nodesById,
            Map<String, List<EvidenceRef>> refsByOwner
    ) {
        return nodesById.values().stream()
                .filter(node -> "validation-constraint".equals(node.kind()) || "security-constraint".equals(node.kind()))
                .sorted(Comparator.comparing(EvidenceNode::label))
                .map(node -> new SemanticSpecDocument.Rule(
                        stableId("rule", node.id()),
                        node.label(),
                        node.kind(),
                        node.attributes().getOrDefault("expression",
                                node.kind().equals("validation-constraint") ? "Validation rule" : "Security rule"),
                        citationsFor(List.of(node.id()), refsByOwner),
                        new SemanticSpecDocument.Confidence("CONFIRMED", "Mapped directly from extracted constraints")
                ))
                .toList();
    }

    private List<SemanticSpecDocument.Scenario> buildScenarios(
            List<SemanticSpecDocument.InterfacePoint> interfaces,
            Map<String, EvidenceNode> nodesById,
            Map<String, List<EvidenceRef>> refsByOwner
    ) {
        List<SemanticSpecDocument.Scenario> scenarios = new ArrayList<>();
        interfaces.forEach(interfacePoint -> interfacePoint.operations().forEach(operation -> scenarios.add(
                new SemanticSpecDocument.Scenario(
                        stableId("scenario", operation.id()),
                        operation.name(),
                        List.of("Given the system is available"),
                        List.of("When " + operation.name() + " is invoked"),
                        List.of("Then the behavior is verified against cited evidence"),
                        operation.evidence(),
                        new SemanticSpecDocument.Confidence("PARTIAL", "Baseline scenario derived from interface evidence")
                )
        )));

        nodesById.values().stream()
                .filter(node -> "documentation-section".equals(node.kind()))
                .filter(node -> node.label().toLowerCase(Locale.ROOT).contains("scenario"))
                .forEach(node -> scenarios.add(new SemanticSpecDocument.Scenario(
                        stableId("scenario", node.id()),
                        node.label(),
                        List.of("Given repository documentation"),
                        List.of("When following " + node.label()),
                        List.of("Then confirm behavior against cited documentation"),
                        citationsFor(List.of(node.id()), refsByOwner),
                        new SemanticSpecDocument.Confidence("PARTIAL", "Derived from documentation evidence")
                )));
        return scenarios;
    }

    private List<SemanticSpecDocument.SemanticNote> buildNotes(
            Map<String, EvidenceNode> nodesById,
            Map<String, List<EvidenceRef>> refsByOwner
    ) {
        List<EvidenceNode> docs = nodesById.values().stream()
                .filter(node -> "documentation-section".equals(node.kind()))
                .sorted(Comparator.comparing(EvidenceNode::label))
                .limit(5)
                .toList();
        return docs.stream()
                .map(node -> new SemanticSpecDocument.SemanticNote(
                        stableId("note", node.id()),
                        "documentation",
                        node.label(),
                        "Relevant documentation anchor available for semantic review",
                        citationsFor(List.of(node.id()), refsByOwner),
                        new SemanticSpecDocument.Confidence("PARTIAL", "Documentation evidence is first-class but requires synthesis review")
                ))
                .toList();
    }

    private List<SemanticSpecDocument.EvidenceCitation> citationsFor(
            List<String> evidenceNodeIds,
            Map<String, List<EvidenceRef>> refsByOwner,
            List<String>... extraEvidenceNodeIds
    ) {
        List<String> allIds = new ArrayList<>(evidenceNodeIds);
        for (List<String> group : extraEvidenceNodeIds) {
            allIds.addAll(group);
        }
        return allIds.stream()
                .distinct()
                .map(nodeId -> {
                    List<EvidenceRef> refs = refsByOwner.getOrDefault(nodeId, List.of());
                    EvidenceRef ref = refs.isEmpty() ? null : refs.getFirst();
                    return new SemanticSpecDocument.EvidenceCitation(
                            nodeId,
                            ref == null ? null : ref.file(),
                            ref == null ? null : ref.startLine(),
                            ref == null ? null : ref.endLine(),
                            "Derived from evidence node " + nodeId
                    );
                })
                .toList();
    }

    private String labelOfFirst(Iterable<EvidenceNode> nodes, Predicate<EvidenceNode> predicate, String fallback) {
        for (EvidenceNode node : nodes) {
            if (predicate.test(node)) {
                return node.label();
            }
        }
        return fallback;
    }

    private String stableId(String prefix, String value) {
        return prefix + ":" + value;
    }
}
