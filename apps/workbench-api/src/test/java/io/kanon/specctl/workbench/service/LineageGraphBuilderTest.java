package io.kanon.specctl.workbench.service;

import io.kanon.specctl.core.extract.ExtractionResult;
import io.kanon.specctl.core.platform.PlatformTypes;
import io.kanon.specctl.ir.CanonicalIr;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class LineageGraphBuilderTest {
    private final LineageGraphBuilder builder = new LineageGraphBuilder();

    @Test
    void buildsStructuralHierarchyWithoutArtifactNodes() {
        PlatformTypes.GraphView graph = builder.build(sampleIr(), new ExtractionResult(List.of(), List.of(), 0.93, List.of()));

        Map<String, PlatformTypes.GraphNode> nodesByType = graph.nodes().stream()
                .collect(java.util.stream.Collectors.toMap(PlatformTypes.GraphNode::type, Function.identity(), (first, ignored) -> first));

        assertThat(graph.nodes()).extracting(PlatformTypes.GraphNode::type)
                .containsExactlyInAnyOrder("service", "bounded-context", "aggregate", "command", "entity", "event");
        assertThat(nodesByType.get("service").parentId()).isNull();
        assertThat(nodesByType.get("bounded-context").parentId()).isEqualTo("svc-orders");
        assertThat(nodesByType.get("aggregate").parentId()).isEqualTo("ctx-core");
        assertThat(nodesByType.get("command").parentId()).isEqualTo("agg-order");
        assertThat(nodesByType.get("entity").parentId()).isEqualTo("agg-order");
        assertThat(nodesByType.get("event").parentId()).isEqualTo("agg-order");
        assertThat(graph.edges()).extracting(PlatformTypes.GraphEdge::label)
                .containsExactlyInAnyOrder("DECLARES", "DECLARES", "HANDLES", "PERSISTS", "EMITS");
    }

    @Test
    void attachesEvidenceAndConflictsToDeepestMatchingStructuralNodeAndRollsThemUp() {
        CanonicalIr ir = sampleIr();
        String aggregatePath = ir.boundedContexts().getFirst().aggregates().getFirst().canonicalPath();
        String commandPath = ir.boundedContexts().getFirst().aggregates().getFirst().commands().getFirst().canonicalPath();
        String entityPath = ir.boundedContexts().getFirst().aggregates().getFirst().entities().getFirst().canonicalPath();

        ExtractionResult extractionResult = new ExtractionResult(
                List.of(),
                List.of(
                        new ExtractionResult.Provenance(commandPath, "OrderController.java", "submitOrder", 18, 22),
                        new ExtractionResult.Provenance(entityPath + "/fields/id", "OrderEntity.java", "id", 5, 5),
                        new ExtractionResult.Provenance("src/main/java/io/acme/Helper.java", "Helper.java", "Helper", 1, 4)
                ),
                0.91,
                List.of(
                        new ExtractionResult.Conflict(aggregatePath, "approved", "alternate", "Aggregate mismatch", true),
                        new ExtractionResult.Conflict(commandPath + "/rules/validation", "approved", "alternate", "Validation drift", false)
                )
        );

        PlatformTypes.GraphView graph = builder.build(ir, extractionResult);
        Map<String, PlatformTypes.GraphNode> nodesById = graph.nodes().stream()
                .collect(java.util.stream.Collectors.toMap(PlatformTypes.GraphNode::id, Function.identity()));

        PlatformTypes.GraphNode service = nodesById.get("svc-orders");
        PlatformTypes.GraphNode context = nodesById.get("ctx-core");
        PlatformTypes.GraphNode aggregate = nodesById.get("agg-order");
        PlatformTypes.GraphNode command = nodesById.get("cmd-submit");
        PlatformTypes.GraphNode entity = nodesById.get("ent-order");

        assertThat(command.stats().evidenceCount()).isEqualTo(1);
        assertThat(command.stats().warningConflictCount()).isEqualTo(1);
        assertThat(command.stats().blockingConflictCount()).isZero();

        assertThat(entity.stats().evidenceCount()).isEqualTo(1);
        assertThat(entity.stats().warningConflictCount()).isZero();

        assertThat(aggregate.stats().evidenceCount()).isEqualTo(2);
        assertThat(aggregate.stats().warningConflictCount()).isEqualTo(1);
        assertThat(aggregate.stats().blockingConflictCount()).isEqualTo(1);

        assertThat(context.stats().evidenceCount()).isEqualTo(2);
        assertThat(context.stats().warningConflictCount()).isEqualTo(1);
        assertThat(context.stats().blockingConflictCount()).isEqualTo(1);

        assertThat(service.stats().evidenceCount()).isEqualTo(3);
        assertThat(service.stats().warningConflictCount()).isEqualTo(1);
        assertThat(service.stats().blockingConflictCount()).isEqualTo(1);
        assertThat(service.stats().boundedContextCount()).isEqualTo(1);
        assertThat(service.stats().aggregateCount()).isEqualTo(1);
        assertThat(service.stats().commandCount()).isEqualTo(1);
        assertThat(service.stats().entityCount()).isEqualTo(1);
        assertThat(service.stats().eventCount()).isEqualTo(1);
    }

    private CanonicalIr sampleIr() {
        CanonicalIr.Command command = new CanonicalIr.Command(
                "SubmitOrder",
                "submit_order",
                "/services/orders/bounded-contexts/core/aggregates/order/commands/submit-order",
                "cmd-submit",
                new CanonicalIr.Http("POST", "/orders"),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        CanonicalIr.Entity entity = new CanonicalIr.Entity(
                "OrderEntity",
                "order_entity",
                "/services/orders/bounded-contexts/core/aggregates/order/entities/order-entity",
                "ent-order",
                "orders",
                List.of(new CanonicalIr.Field("id", "id", "/services/orders/id", "field-id", "uuid", true, false))
        );
        CanonicalIr.Event event = new CanonicalIr.Event(
                "OrderSubmitted",
                "order_submitted",
                "/services/orders/bounded-contexts/core/aggregates/order/events/order-submitted",
                "evt-order-submitted",
                "orders.submitted",
                new CanonicalIr.EventSchema("json", 1),
                List.of()
        );
        CanonicalIr.Aggregate aggregate = new CanonicalIr.Aggregate(
                "Order",
                "order",
                "/services/orders/bounded-contexts/core/aggregates/order",
                "agg-order",
                null,
                List.of(entity),
                null,
                List.of(command),
                List.of(event),
                List.of()
        );
        CanonicalIr.BoundedContext context = new CanonicalIr.BoundedContext(
                "Core",
                "core",
                "/services/orders/bounded-contexts/core",
                "ctx-core",
                List.of(aggregate)
        );

        return new CanonicalIr(
                1,
                "1.0",
                null,
                new CanonicalIr.Service("Orders", "io.acme.orders", "orders", "/services/orders", "svc-orders"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(context),
                null
        );
    }
}
