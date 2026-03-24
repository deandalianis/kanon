import { describe, expect, it } from "vitest";
import type { ExtractionConflict, ExtractionProvenance, GraphView } from "../../types";
import {
  buildSearchResults,
  createExplorerIndex,
  deriveMaterializedArtifacts,
  deriveNeighborhood,
  deriveVisibleStructuralIds,
  expandAncestors
} from "./graphExplorerModel";

describe("graphExplorerModel", () => {
  it("shows the service overview with bounded contexts by default", () => {
    const index = createExplorerIndex(sampleGraph(), sampleProvenance(), sampleConflicts());

    expect(deriveVisibleStructuralIds(index, new Set<string>(), { onlyBlockers: false, onlyEvidenceGaps: false }))
      .toEqual(["svc-orders", "ctx-core", "ctx-ops"]);
  });

  it("reveals aggregates and leaves when their parents are expanded", () => {
    const index = createExplorerIndex(sampleGraph(), sampleProvenance(), sampleConflicts());

    expect(
      deriveVisibleStructuralIds(index, new Set(["ctx-core"]), { onlyBlockers: false, onlyEvidenceGaps: false })
    ).toEqual(["svc-orders", "ctx-core", "agg-order", "ctx-ops"]);

    expect(
      deriveVisibleStructuralIds(index, new Set(["ctx-core", "agg-order"]), {
        onlyBlockers: false,
        onlyEvidenceGaps: false
      })
    ).toEqual(["svc-orders", "ctx-core", "agg-order", "cmd-submit", "ent-order", "evt-submitted", "ctx-ops"]);
  });

  it("builds the selected neighborhood from ancestors and direct descendants", () => {
    const index = createExplorerIndex(sampleGraph(), sampleProvenance(), sampleConflicts());

    expect([...deriveNeighborhood(index, "agg-order")].sort()).toEqual([
      "agg-order",
      "cmd-submit",
      "ctx-core",
      "ent-order",
      "evt-submitted",
      "svc-orders"
    ]);
  });

  it("searches labels, paths, and metadata and expands ancestors for the chosen result", () => {
    const index = createExplorerIndex(sampleGraph(), sampleProvenance(), sampleConflicts());

    expect(buildSearchResults(index, "POST")[0]?.id).toBe("cmd-submit");
    expect(buildSearchResults(index, "orders.submitted")[0]?.id).toBe("evt-submitted");
    expect([...expandAncestors(index, "cmd-submit", new Set<string>())].sort()).toEqual(["agg-order", "ctx-core"]);
  });

  it("filters to blocking nodes and uncovered nodes while preserving ancestor chains", () => {
    const index = createExplorerIndex(sampleGraph(), sampleProvenance(), sampleConflicts());

    expect(
      deriveVisibleStructuralIds(index, new Set<string>(), { onlyBlockers: true, onlyEvidenceGaps: false }).sort()
    ).toEqual(["agg-order", "ctx-core", "svc-orders"]);

    expect(
      deriveVisibleStructuralIds(index, new Set<string>(), { onlyBlockers: false, onlyEvidenceGaps: true }).sort()
    ).toEqual(["agg-order", "ctx-core", "ctx-ops", "evt-submitted", "svc-orders"]);
  });

  it("materializes only the artifacts attached to the selected structural node", () => {
    const index = createExplorerIndex(sampleGraph(), sampleProvenance(), sampleConflicts());

    const commandArtifacts = deriveMaterializedArtifacts(
      index,
      ["svc-orders", "ctx-core", "agg-order", "cmd-submit"],
      "cmd-submit",
      true,
      true
    );
    const aggregateArtifacts = deriveMaterializedArtifacts(
      index,
      ["svc-orders", "ctx-core", "agg-order"],
      "agg-order",
      true,
      true
    );

    expect(commandArtifacts.map((artifact) => artifact.kind)).toEqual(["evidence"]);
    expect(aggregateArtifacts.map((artifact) => artifact.kind)).toEqual(["conflict"]);
  });
});

function sampleGraph(): GraphView {
  return {
    nodes: [
      {
        id: "svc-orders",
        label: "Orders",
        type: "service",
        path: "/services/orders",
        parentId: null,
        stats: {
          evidenceCount: 3,
          warningConflictCount: 1,
          blockingConflictCount: 1,
          boundedContextCount: 2,
          aggregateCount: 1,
          commandCount: 1,
          entityCount: 1,
          eventCount: 1
        },
        metadata: { basePackage: "io.acme.orders" }
      },
      {
        id: "ctx-core",
        label: "Core",
        type: "bounded-context",
        path: "/services/orders/bounded-contexts/core",
        parentId: "svc-orders",
        stats: {
          evidenceCount: 2,
          warningConflictCount: 1,
          blockingConflictCount: 1,
          boundedContextCount: 0,
          aggregateCount: 1,
          commandCount: 1,
          entityCount: 1,
          eventCount: 1
        },
        metadata: {}
      },
      {
        id: "ctx-ops",
        label: "Ops",
        type: "bounded-context",
        path: "/services/orders/bounded-contexts/ops",
        parentId: "svc-orders",
        stats: {
          evidenceCount: 0,
          warningConflictCount: 0,
          blockingConflictCount: 0,
          boundedContextCount: 0,
          aggregateCount: 0,
          commandCount: 0,
          entityCount: 0,
          eventCount: 0
        },
        metadata: {}
      },
      {
        id: "agg-order",
        label: "Order",
        type: "aggregate",
        path: "/services/orders/bounded-contexts/core/aggregates/order",
        parentId: "ctx-core",
        stats: {
          evidenceCount: 2,
          warningConflictCount: 1,
          blockingConflictCount: 1,
          boundedContextCount: 0,
          aggregateCount: 0,
          commandCount: 1,
          entityCount: 1,
          eventCount: 1
        },
        metadata: {}
      },
      {
        id: "cmd-submit",
        label: "SubmitOrder",
        type: "command",
        path: "/services/orders/bounded-contexts/core/aggregates/order/commands/submit-order",
        parentId: "agg-order",
        stats: {
          evidenceCount: 1,
          warningConflictCount: 0,
          blockingConflictCount: 0,
          boundedContextCount: 0,
          aggregateCount: 0,
          commandCount: 0,
          entityCount: 0,
          eventCount: 0
        },
        metadata: { method: "POST", httpPath: "/orders" }
      },
      {
        id: "ent-order",
        label: "OrderEntity",
        type: "entity",
        path: "/services/orders/bounded-contexts/core/aggregates/order/entities/order-entity",
        parentId: "agg-order",
        stats: {
          evidenceCount: 1,
          warningConflictCount: 0,
          blockingConflictCount: 0,
          boundedContextCount: 0,
          aggregateCount: 0,
          commandCount: 0,
          entityCount: 0,
          eventCount: 0
        },
        metadata: { table: "orders", fieldCount: 8 }
      },
      {
        id: "evt-submitted",
        label: "OrderSubmitted",
        type: "event",
        path: "/services/orders/bounded-contexts/core/aggregates/order/events/order-submitted",
        parentId: "agg-order",
        stats: {
          evidenceCount: 0,
          warningConflictCount: 0,
          blockingConflictCount: 0,
          boundedContextCount: 0,
          aggregateCount: 0,
          commandCount: 0,
          entityCount: 0,
          eventCount: 0
        },
        metadata: { topic: "orders.submitted" }
      }
    ],
    edges: [
      { id: "svc->core", source: "svc-orders", target: "ctx-core", label: "DECLARES" },
      { id: "svc->ops", source: "svc-orders", target: "ctx-ops", label: "DECLARES" },
      { id: "core->order", source: "ctx-core", target: "agg-order", label: "DECLARES" },
      { id: "order->submit", source: "agg-order", target: "cmd-submit", label: "HANDLES" },
      { id: "order->entity", source: "agg-order", target: "ent-order", label: "PERSISTS" },
      { id: "order->event", source: "agg-order", target: "evt-submitted", label: "EMITS" }
    ]
  };
}

function sampleProvenance(): ExtractionProvenance[] {
  return [
    {
      path: "/services/orders/bounded-contexts/core/aggregates/order/commands/submit-order",
      file: "OrderController.java",
      symbol: "submitOrder",
      startLine: 18,
      endLine: 22
    },
    {
      path: "/services/orders/bounded-contexts/core/aggregates/order/entities/order-entity/fields/id",
      file: "OrderEntity.java",
      symbol: "id",
      startLine: 5,
      endLine: 5
    },
    {
      path: "src/main/java/io/acme/orders/Helper.java",
      file: "Helper.java",
      symbol: "Helper",
      startLine: 1,
      endLine: 4
    }
  ];
}

function sampleConflicts(): ExtractionConflict[] {
  return [
    {
      path: "/services/orders/bounded-contexts/core/aggregates/order",
      preferredSource: "approved",
      alternateSource: "alternate",
      message: "Aggregate mismatch",
      fatal: true
    },
    {
      path: "src/main/java/io/acme/orders/Helper.java",
      preferredSource: "approved",
      alternateSource: "alternate",
      message: "Helper drift",
      fatal: false
    }
  ];
}
