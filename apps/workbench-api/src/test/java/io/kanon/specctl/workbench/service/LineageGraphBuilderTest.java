package io.kanon.specctl.workbench.service;

import io.kanon.specctl.core.platform.PlatformTypes;
import io.kanon.specctl.extraction.evidence.EvidenceConflictRecord;
import io.kanon.specctl.extraction.evidence.EvidenceNode;
import io.kanon.specctl.extraction.evidence.EvidenceRef;
import io.kanon.specctl.extraction.evidence.EvidenceSnapshot;
import io.kanon.specctl.semantic.SemanticSpecDocument;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LineageGraphBuilderTest {
    private final LineageGraphBuilder builder = new LineageGraphBuilder();

    @Test
    void buildsSemanticHierarchyForServiceInterfacesAndArtifacts() {
        PlatformTypes.GraphView graph = builder.build(sampleSpec(false), sampleEvidence());

        Map<String, PlatformTypes.GraphNode> nodesById = graph.nodes().stream()
                .collect(java.util.stream.Collectors.toMap(PlatformTypes.GraphNode::id, Function.identity()));

        assertThat(graph.nodes()).extracting(PlatformTypes.GraphNode::type)
                .containsExactlyInAnyOrder(
                        "service",
                        "interface",
                        "operation",
                        "datastore",
                        "integration",
                        "workflow",
                        "rule",
                        "scenario",
                        "note"
                );
        assertThat(nodesById.get("svc-orders").parentId()).isNull();
        assertThat(nodesById.get("if-http").parentId()).isEqualTo("svc-orders");
        assertThat(nodesById.get("op-submit-order").parentId()).isEqualTo("if-http");
        assertThat(nodesById.get("ds-orders").parentId()).isEqualTo("svc-orders");
        assertThat(nodesById.get("int-payments").parentId()).isEqualTo("svc-orders");
        assertThat(graph.edges()).extracting(PlatformTypes.GraphEdge::label)
                .containsExactlyInAnyOrder(
                        "DECLARES",
                        "DECLARES",
                        "USES",
                        "INTEGRATES_WITH",
                        "RUNS",
                        "CONSTRAINS",
                        "DESCRIBES",
                        "NOTES"
                );
    }

    @Test
    void rollsEvidenceCountsUpToAncestorsAndFlagsBrokenServiceCitations() {
        PlatformTypes.GraphView graph = builder.build(sampleSpec(true), sampleEvidence());
        Map<String, PlatformTypes.GraphNode> nodesById = graph.nodes().stream()
                .collect(java.util.stream.Collectors.toMap(PlatformTypes.GraphNode::id, Function.identity()));

        PlatformTypes.GraphNode service = nodesById.get("svc-orders");
        PlatformTypes.GraphNode httpInterface = nodesById.get("if-http");
        PlatformTypes.GraphNode operation = nodesById.get("op-submit-order");

        assertThat(operation.stats().evidenceCount()).isEqualTo(1);
        assertThat(httpInterface.stats().evidenceCount()).isEqualTo(2);
        assertThat(service.stats().evidenceCount()).isEqualTo(8);

        assertThat(service.stats().blockingConflictCount()).isEqualTo(1);
        assertThat(service.stats().warningConflictCount()).isZero();

        assertThat(service.stats().boundedContextCount()).isEqualTo(1);
        assertThat(service.stats().aggregateCount()).isEqualTo(1);
        assertThat(service.stats().commandCount()).isEqualTo(1);
        assertThat(service.stats().entityCount()).isEqualTo(1);
        assertThat(service.stats().eventCount()).isEqualTo(1);
    }

    private SemanticSpecDocument sampleSpec(boolean includeBrokenServiceCitation) {
        List<SemanticSpecDocument.EvidenceCitation> serviceEvidence = includeBrokenServiceCitation
                ? List.of(
                        citation("ev-service", "README.md", 1, 8, "Service overview"),
                        citation("missing-evidence", "README.md", 9, 12, "Broken lineage")
                )
                : List.of(citation("ev-service", "README.md", 1, 8, "Service overview"));

        return new SemanticSpecDocument(
                1,
                "0.1.0",
                new SemanticSpecDocument.Service(
                        "svc-orders",
                        "Orders Service",
                        "io.acme.orders",
                        "Handles order submission.",
                        serviceEvidence,
                        confidence("high", "Derived from code and docs"),
                        Map.of("runtime", "spring")
                ),
                List.of(new SemanticSpecDocument.InterfacePoint(
                        "if-http",
                        "HTTP Surface",
                        "inbound",
                        "http",
                        "/orders",
                        "Primary entrypoints.",
                        List.of(new SemanticSpecDocument.Operation(
                                "op-submit-order",
                                "SubmitOrder",
                                "POST",
                                "/orders",
                                "Creates a new order.",
                                List.of(citation("ev-endpoint", "OrderController.java", 18, 28, "Endpoint mapping")),
                                confidence("high", "Direct mapping")
                        )),
                        List.of(citation("ev-interface", "OrderController.java", 12, 28, "Controller class")),
                        confidence("high", "HTTP endpoints discovered")
                )),
                List.of(new SemanticSpecDocument.DataStore(
                        "ds-orders",
                        "orders-db",
                        "postgresql",
                        "Order persistence.",
                        List.of("orders", "db/changelog/orders.xml"),
                        List.of(citation("ev-datastore", "db/changelog/orders.xml", 1, 42, "DDL migration")),
                        confidence("medium", "Observed migration")
                )),
                List.of(new SemanticSpecDocument.Integration(
                        "int-payments",
                        "Payments Gateway",
                        "http-client",
                        "outbound",
                        "payments-service",
                        "Calls payments.",
                        List.of("PaymentClient"),
                        List.of(citation("ev-integration", "PaymentClient.java", 6, 22, "Remote client")),
                        confidence("medium", "Client artifact")
                )),
                List.of(new SemanticSpecDocument.Workflow(
                        "wf-submit-order",
                        "Submit order",
                        "Accept order and persist it.",
                        List.of(new SemanticSpecDocument.WorkflowStep(
                                "step-1",
                                "operation",
                                "Invoke submit order endpoint.",
                                List.of(citation("ev-endpoint", "OrderController.java", 18, 28, "Workflow entry"))
                        )),
                        List.of(citation("ev-workflow", "OrderService.java", 14, 47, "Application flow")),
                        confidence("medium", "Inferred from call graph")
                )),
                List.of(new SemanticSpecDocument.Rule(
                        "rule-submit",
                        "Order total must be positive",
                        "validation",
                        "Reject orders with non-positive totals.",
                        List.of(citation("ev-rule", "CreateOrderRequest.java", 8, 16, "Validation annotation")),
                        confidence("high", "Direct validation")
                )),
                List.of(new SemanticSpecDocument.Scenario(
                        "sc-happy-path",
                        "Submit order happy path",
                        List.of("An authenticated client"),
                        List.of("The client submits a valid order"),
                        List.of("The order is persisted"),
                        List.of(citation("ev-scenario", "README.md", 20, 28, "Business flow")),
                        confidence("low", "Documented scenario")
                )),
                List.of(new SemanticSpecDocument.SemanticNote(
                        "note-1",
                        "coverage",
                        "Payment fallback unclear",
                        "No retry contract was found.",
                        List.of(citation("ev-note", "PaymentClient.java", 6, 22, "No retry semantics")),
                        confidence("low", "Negative observation")
                ))
        );
    }

    private EvidenceSnapshot sampleEvidence() {
        return new EvidenceSnapshot(
                1,
                "/tmp/orders",
                "build.gradle.kts",
                "21",
                List.of(
                        node("ev-service", "documentation-section", "Service overview", "README.md"),
                        node("ev-interface", "http-interface", "OrderController", "src/main/java/io/acme/orders/OrderController.java"),
                        node("ev-endpoint", "http-endpoint", "POST /orders", "src/main/java/io/acme/orders/OrderController.java"),
                        node("ev-datastore", "database-migration", "orders.xml", "src/main/resources/db/changelog/orders.xml"),
                        node("ev-integration", "integration-artifact", "PaymentClient", "src/main/java/io/acme/orders/PaymentClient.java"),
                        node("ev-workflow", "call-path", "Submit order flow", "src/main/java/io/acme/orders/OrderService.java"),
                        node("ev-rule", "validation-rule", "Positive total", "src/main/java/io/acme/orders/CreateOrderRequest.java"),
                        node("ev-scenario", "documentation-section", "Happy path", "README.md"),
                        node("ev-note", "integration-artifact", "Payment client", "src/main/java/io/acme/orders/PaymentClient.java")
                ),
                List.of(),
                List.of(
                        new EvidenceRef("ev-endpoint", "ev-endpoint", "OrderController.java", 18, 28, "submit order"),
                        new EvidenceRef("ev-datastore", "ev-datastore", "orders.xml", 1, 42, "create table orders")
                ),
                List.of(),
                List.of(new EvidenceConflictRecord(
                        "conflict-1",
                        "warning",
                        "Unused adapter output",
                        List.of("ev-note")
                )),
                List.of()
        );
    }

    private EvidenceNode node(String id, String kind, String label, String path) {
        return new EvidenceNode(id, kind, label, path, Map.of());
    }

    private SemanticSpecDocument.EvidenceCitation citation(
            String evidenceNodeId,
            String file,
            Integer startLine,
            Integer endLine,
            String rationale
    ) {
        return new SemanticSpecDocument.EvidenceCitation(evidenceNodeId, file, startLine, endLine, rationale);
    }

    private SemanticSpecDocument.Confidence confidence(String level, String rationale) {
        return new SemanticSpecDocument.Confidence(level, rationale);
    }
}
