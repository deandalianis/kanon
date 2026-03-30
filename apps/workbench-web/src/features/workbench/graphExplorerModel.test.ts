import {describe, expect, it} from "vitest";
import type {ExtractionSnapshot, GraphView} from "../../types";
import {
    buildSearchResults,
    createExplorerIndex,
    deriveMaterializedArtifacts,
    deriveNeighborhood,
    deriveVisibleStructuralIds,
    expandAncestors
} from "./graphExplorerModel";

describe("graphExplorerModel", () => {
    it("shows the service overview with top-level semantic nodes by default", () => {
        const index = createExplorerIndex(sampleGraph(), sampleExtraction());

        expect(deriveVisibleStructuralIds(index, new Set<string>(), {onlyBlockers: false, onlyEvidenceGaps: false}))
            .toEqual(["svc-orders", "if-http", "ds-orders", "int-payments", "wf-submit"]);
    });

    it("reveals operations when their interface parent is expanded", () => {
        const index = createExplorerIndex(sampleGraph(), sampleExtraction());

        expect(
            deriveVisibleStructuralIds(index, new Set(["if-http"]), {onlyBlockers: false, onlyEvidenceGaps: false})
        ).toEqual(["svc-orders", "if-http", "op-submit", "ds-orders", "int-payments", "wf-submit"]);
    });

    it("builds the selected neighborhood from ancestors and direct descendants", () => {
        const index = createExplorerIndex(sampleGraph(), sampleExtraction());

        expect([...deriveNeighborhood(index, "if-http")].sort()).toEqual([
            "if-http",
            "op-submit",
            "svc-orders"
        ]);
    });

    it("searches labels, paths, and metadata and expands ancestors for the chosen result", () => {
        const index = createExplorerIndex(sampleGraph(), sampleExtraction());

        expect(buildSearchResults(index, "payments")[0]?.id).toBe("int-payments");
        expect(buildSearchResults(index, "SubmitOrder")[0]?.id).toBe("op-submit");
        expect([...expandAncestors(index, "op-submit", new Set<string>())]).toEqual(["if-http"]);
    });

    it("filters to blocking nodes and uncovered nodes while preserving ancestor chains", () => {
        const index = createExplorerIndex(sampleGraph(), sampleExtraction());

        expect(
            deriveVisibleStructuralIds(index, new Set<string>(), {onlyBlockers: true, onlyEvidenceGaps: false}).sort()
        ).toEqual(["svc-orders", "wf-submit"]);

        expect(
            deriveVisibleStructuralIds(index, new Set<string>(), {onlyBlockers: false, onlyEvidenceGaps: true}).sort()
        ).toEqual(["int-payments", "svc-orders"]);
    });

    it("materializes only the artifacts attached to the selected structural node", () => {
        const index = createExplorerIndex(sampleGraph(), sampleExtraction());

        const operationArtifacts = deriveMaterializedArtifacts(
            index,
            ["svc-orders", "if-http", "op-submit"],
            "op-submit",
            true,
            true
        );
        const workflowArtifacts = deriveMaterializedArtifacts(
            index,
            ["svc-orders", "wf-submit"],
            "wf-submit",
            true,
            true
        );

        expect(operationArtifacts.map((artifact) => artifact.kind)).toEqual(["evidence"]);
        expect(workflowArtifacts.map((artifact) => artifact.kind)).toEqual(["conflict"]);
    });
});

function sampleGraph(): GraphView {
    return {
        nodes: [
            {
                id: "svc-orders",
                label: "Orders Service",
                type: "service",
                path: "io.acme.orders",
                parentId: null,
                stats: {
                    evidenceCount: 8,
                    warningConflictCount: 0,
                    blockingConflictCount: 1,
                    boundedContextCount: 1,
                    aggregateCount: 1,
                    commandCount: 1,
                    entityCount: 1,
                    eventCount: 1
                },
                metadata: {summary: "Handles order submission"}
            },
            {
                id: "if-http",
                label: "HTTP Surface",
                type: "interface",
                path: "/orders",
                parentId: "svc-orders",
                stats: {
                    evidenceCount: 2,
                    warningConflictCount: 0,
                    blockingConflictCount: 0,
                    boundedContextCount: 0,
                    aggregateCount: 0,
                    commandCount: 1,
                    entityCount: 0,
                    eventCount: 0
                },
                metadata: {protocol: "http", kind: "inbound"}
            },
            {
                id: "op-submit",
                label: "SubmitOrder",
                type: "operation",
                path: "/orders",
                parentId: "if-http",
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
                metadata: {method: "POST", summary: "Creates a new order"}
            },
            {
                id: "ds-orders",
                label: "orders-db",
                type: "datastore",
                path: "postgresql",
                parentId: "svc-orders",
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
                metadata: {artifacts: "orders, db/changelog/orders.xml"}
            },
            {
                id: "int-payments",
                label: "Payments Gateway",
                type: "integration",
                path: "payments-service",
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
                metadata: {direction: "outbound", kind: "http-client"}
            },
            {
                id: "wf-submit",
                label: "Submit order",
                type: "workflow",
                path: "Submit order",
                parentId: "svc-orders",
                stats: {
                    evidenceCount: 1,
                    warningConflictCount: 0,
                    blockingConflictCount: 1,
                    boundedContextCount: 0,
                    aggregateCount: 0,
                    commandCount: 0,
                    entityCount: 0,
                    eventCount: 0
                },
                metadata: {summary: "Accept order and persist it"}
            }
        ],
        edges: [
            {id: "svc-if", source: "svc-orders", target: "if-http", label: "DECLARES"},
            {id: "if-op", source: "if-http", target: "op-submit", label: "DECLARES"},
            {id: "svc-ds", source: "svc-orders", target: "ds-orders", label: "USES"},
            {id: "svc-int", source: "svc-orders", target: "int-payments", label: "INTEGRATES_WITH"},
            {id: "svc-wf", source: "svc-orders", target: "wf-submit", label: "RUNS"}
        ]
    };
}

function sampleExtraction(): ExtractionSnapshot {
    return {
        manifest: {
            buildResolutionPath: "build-resolution.json",
            sourceEvidencePath: "source-evidence.json",
            bytecodeEvidencePath: "bytecode-evidence.json",
            runtimeEvidencePath: "runtime-evidence.json",
            mergedEvidencePath: "merged-evidence.json",
            codebaseIrPath: "codebase-ir.json",
            confidenceReportPath: "confidence-report.json"
        },
        buildResolution: {
            buildTool: "gradle",
            projectRoot: "/tmp/orders",
            buildFile: "build.gradle.kts",
            rootModule: ":orders",
            buildCommand: ["./gradlew", "classes"],
            modules: [],
            sourceRoots: [],
            generatedSourceRoots: [],
            compileClasspath: [],
            runtimeClasspath: [],
            classOutputDirectories: [],
            resourceOutputDirectories: [],
            javaRelease: "21",
            mainClass: "io.acme.orders.Application",
            capabilities: {
                plainJava: false,
                spring: true,
                springBoot: true,
                springWebMvc: true,
                springWebFlux: false,
                jpa: true,
                beanValidation: true,
                springSecurity: false
            },
            buildSucceeded: true,
            diagnostics: []
        },
        sourceEvidence: emptyEvidenceBundle(),
        bytecodeEvidence: emptyEvidenceBundle(),
        runtimeEvidence: {...emptyEvidenceBundle(), bootSucceeded: false},
        mergedEvidence: emptyEvidenceBundle(),
        codebaseIr: {
            ...emptyEvidenceBundle(),
            schemaVersion: 1,
            specVersion: "0.1.0",
            projectRoot: "/tmp/orders",
            mainClass: "io.acme.orders.Application",
            capabilities: {
                plainJava: false,
                spring: true,
                springBoot: true,
                springWebMvc: true,
                springWebFlux: false,
                jpa: true,
                beanValidation: true,
                springSecurity: false
            }
        },
        confidenceReport: {
            trusted: true,
            domains: {}
        },
        evidenceSnapshot: {
            schemaVersion: 1,
            projectRoot: "/tmp/orders",
            buildFile: "build.gradle.kts",
            javaRelease: "21",
            nodes: [
                {
                    id: "ev-endpoint",
                    kind: "http-endpoint",
                    label: "POST /orders",
                    path: "/orders",
                    attributes: {}
                },
                {
                    id: "ev-workflow",
                    kind: "documentation-section",
                    label: "Submit order flow",
                    path: "Submit order",
                    attributes: {}
                }
            ],
            edges: [],
            refs: [
                {
                    ownerId: "op-submit",
                    evidenceNodeId: "ev-endpoint",
                    file: "OrderController.java",
                    startLine: 18,
                    endLine: 28,
                    excerpt: "submit order"
                }
            ],
            adapters: [],
            conflicts: [
                {
                    id: "conflict-1",
                    severity: "blocking",
                    summary: "Workflow approval is incomplete",
                    evidenceNodeIds: ["ev-workflow"]
                }
            ],
            confidence: []
        }
    };
}

function emptyEvidenceBundle() {
    return {
        types: [],
        endpoints: [],
        beans: [],
        jpaEntities: [],
        validations: [],
        securities: [],
        conflicts: [],
        provenance: [],
        diagnostics: []
    };
}
