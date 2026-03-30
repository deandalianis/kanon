import {type ReactNode, useEffect, useRef, useState} from "react";
import {
    Background,
    BackgroundVariant,
    Controls,
    type Edge,
    MarkerType,
    type Node,
    Position,
    ReactFlow,
    type ReactFlowInstance
} from "@xyflow/react";
import ELK from "elkjs/lib/elk.bundled.js";
import type {BootstrapRunMetadata, ExtractionSnapshot, GraphEdge, GraphNode, GraphView, RunSummary} from "../../../types";
import {buildHotspots, type ExplorerArtifact, type ExplorerHotspot} from "../graphExplorerModel";
import {useGraphExplorerModel} from "../useGraphExplorerModel";
import type {GraphSummary} from "../types";
import {EmptyState, InfoListRow, KeyValueList, Panel, SectionHeader, StatusBadge} from "./primitives";

const elk = new ELK();

type FlowLabelData = {
    label: ReactNode;
};

type NodeSize = {
    width: number;
    height: number;
};

const EDGE_TONES: Record<string, string> = {
    DECLARES: "#7fb3ff",
    USES: "#78c2ad",
    INTEGRATES_WITH: "#f2cc8f",
    RUNS: "#8ed1a6",
    CONSTRAINS: "#ffb86b",
    DESCRIBES: "#b39ddb",
    NOTES: "#9fb2cb",
    EVIDENCED_BY: "#7fb3ff",
    BLOCKS: "#e07a5f",
    WARNS: "#f2cc8f"
};

export function GraphStage({
                               hasExtractionRun,
                               graph,
                               graphLoading,
                               graphSummary,
                               extraction,
                               latestBootstrapRun,
                               bootstrapMetadata,
                               onRefreshKnowledge,
                               refreshPending
                           }: {
    hasExtractionRun: boolean;
    graph?: GraphView;
    graphLoading: boolean;
    graphSummary: GraphSummary;
    extraction?: ExtractionSnapshot;
    latestBootstrapRun: RunSummary | null;
    bootstrapMetadata: BootstrapRunMetadata | null;
    onRefreshKnowledge: () => void;
    refreshPending: boolean;
}) {
    const model = useGraphExplorerModel(graph, extraction);
    const [layoutMap, setLayoutMap] = useState<Record<string, { x: number; y: number }>>({});
    const [layoutVersion, setLayoutVersion] = useState(0);
    const [flowInstance, setFlowInstance] = useState<ReactFlowInstance<Node<FlowLabelData>, Edge> | null>(null);
    const handledFocusToken = useRef(0);

    useEffect(() => {
        if (!model.visibleStructuralNodes.length) {
            setLayoutMap({});
            return;
        }

        let cancelled = false;

        async function runLayout() {
            const layout = await elk.layout({
                id: "lineage-root",
                layoutOptions: {
                    "elk.algorithm": "layered",
                    "elk.direction": "RIGHT",
                    "elk.layered.spacing.nodeNodeBetweenLayers": "160",
                    "elk.spacing.nodeNode": "42",
                    "elk.padding": "[top=32,left=32,bottom=32,right=32]"
                },
                children: model.visibleStructuralNodes.map((node) => {
                    const size = getStructuralNodeSize(node.type);
                    return {
                        id: node.id,
                        width: size.width,
                        height: size.height
                    };
                }),
                edges: model.visibleStructuralEdges.map((edge) => ({
                    id: edge.id,
                    sources: [edge.source],
                    targets: [edge.target]
                }))
            });

            if (cancelled) {
                return;
            }

            const nextMap = Object.fromEntries(
                (layout.children ?? []).map((child) => [child.id, {x: child.x ?? 0, y: child.y ?? 0}])
            );

            setLayoutMap(nextMap);
            setLayoutVersion((current) => current + 1);
        }

        void runLayout();

        return () => {
            cancelled = true;
        };
    }, [model.visibleStructuralEdges, model.visibleStructuralNodes]);

    useEffect(() => {
        if (!flowInstance || !layoutVersion) {
            return;
        }

        requestAnimationFrame(() => {
            flowInstance.fitView({duration: 240, padding: 0.18});
        });
    }, [flowInstance, layoutVersion]);

    useEffect(() => {
        if (!flowInstance || !model.focusRequest.id || handledFocusToken.current === model.focusRequest.token) {
            return;
        }

        if (!layoutMap[model.focusRequest.id]) {
            return;
        }

        handledFocusToken.current = model.focusRequest.token;
        const focusTargetId = model.focusRequest.id;
        requestAnimationFrame(() => {
            const focusNode = flowInstance.getNode(focusTargetId);
            if (!focusNode) {
                return;
            }
            void flowInstance.fitView({
                nodes: [focusNode],
                duration: 260,
                padding: 0.42
            });
        });
    }, [flowInstance, layoutMap, model.focusRequest]);

    useEffect(() => {
        function onKeyDown(event: KeyboardEvent) {
            if (event.key !== "Escape") {
                return;
            }
            model.clearFocus();
        }

        window.addEventListener("keydown", onKeyDown);
        return () => window.removeEventListener("keydown", onKeyDown);
    }, [model]);

    const structuralFlowNodes = model.visibleStructuralNodes.map((node) =>
        buildStructuralFlowNode(node, layoutMap[node.id], {
            dimmed: model.isDimmed(node.id),
            expanded: model.isExpanded(node.id),
            selectable: true,
            toggleExpanded: () => model.toggleExpanded(node.id)
        })
    );
    const structuralFlowEdges = model.visibleStructuralEdges.map((edge) =>
        buildStructuralFlowEdge(edge, model.neighborhoodIds, model.selectedStructuralId)
    );
    const artifactFlowNodes = buildArtifactFlowNodes(
        model.materializedArtifacts,
        structuralFlowNodes,
        model.selectedId
    );
    const artifactFlowEdges = buildArtifactFlowEdges(model.materializedArtifacts, model.selectedStructuralId);
    const flowNodes = [...structuralFlowNodes, ...artifactFlowNodes];
    const flowEdges = [...structuralFlowEdges, ...artifactFlowEdges];
    const hotspotGroups = buildHotspots(model.index);
    const graphTone = graphSummary.blockingConflictCount
        ? "danger"
        : graphSummary.warningConflictCount
            ? "warning"
            : graphSummary.nodes
                ? "info"
                : "neutral";
    const runningStage = bootstrapMetadata?.stages.find((stage) => stage.status === "RUNNING") ?? null;
    const failedStage = bootstrapMetadata?.stages.find((stage) => stage.status === "FAILED") ?? null;
    const graphEmptyTitle = latestBootstrapRun?.status === "FAILED"
        ? "Graph refresh failed"
        : runningStage?.kind === "SYNTHESIS" || runningStage?.kind === "APPROVE"
            ? "Graph rebuild queued"
            : runningStage?.kind === "GRAPH_REBUILD"
                ? "Graph rebuild in progress"
                : "No lineage graph loaded";
    const graphEmptyDetail = latestBootstrapRun?.status === "FAILED"
        ? failedStage?.detail || latestBootstrapRun.logText || "The latest bootstrap failed before a graph was produced."
        : runningStage?.kind === "SYNTHESIS" || runningStage?.kind === "APPROVE"
            ? "The graph is waiting for semantic synthesis and approval to finish before rebuild can start."
            : runningStage?.kind === "GRAPH_REBUILD"
                ? "The graph projection is currently running."
                : "Open the graph stage after extraction to build the structural lineage for this workspace.";

    return (
        <Panel className="graph-stage-panel">
            <SectionHeader
                eyebrow="Graph"
                title="Lineage explorer"
                description="Browse architecture, isolate blockers, and trace extraction evidence without dumping the entire graph at once."
                badge={<StatusBadge
                    tone={graphTone}>{graphSummary.nodes ? `${graphSummary.nodes} nodes` : "not loaded"}</StatusBadge>}
                actions={
                    <button
                        type="button"
                        className="primary-button"
                        onClick={onRefreshKnowledge}
                        disabled={refreshPending}
                    >
                        {refreshPending ? "Refreshing..." : "Refresh knowledge"}
                    </button>
                }
            />

            {!hasExtractionRun ? (
                <EmptyState
                    title="Graph unavailable"
                    detail="Run extraction before opening the lineage explorer so evidence anchors and conflicts can be attached to structural nodes."
                    action={
                        <button
                            type="button"
                            className="primary-button"
                            onClick={onRefreshKnowledge}
                            disabled={refreshPending}
                        >
                            {refreshPending ? "Refreshing..." : "Refresh knowledge"}
                        </button>
                    }
                />
            ) : graphLoading ? (
                <EmptyState
                    title="Loading lineage explorer"
                    detail="The graph structure and extraction evidence are being projected into the explorer."
                />
            ) : graph?.nodes.length ? (
                <div className="graph-explorer">
                    <div className="graph-toolbar">
                        <div className="graph-search">
                            <input
                                className="graph-search-input"
                                value={model.searchQuery}
                                onChange={(event) => model.setSearchQuery(event.target.value)}
                                placeholder="Search nodes by name, path, or metadata"
                                aria-label="Search lineage nodes"
                            />
                            {model.searchResults.length ? (
                                <div className="graph-search-results">
                                    {model.searchResults.map((result) => (
                                        <button
                                            key={result.id}
                                            type="button"
                                            className="graph-search-result"
                                            onClick={() => model.selectSearchResult(result.id)}
                                        >
                                            <strong>{result.label}</strong>
                                            <span>{formatTypeLabel(result.type)} · {result.path}</span>
                                        </button>
                                    ))}
                                </div>
                            ) : null}
                        </div>

                        <div className="graph-toolbar-actions">
                            <button
                                type="button"
                                className={`secondary-button ${model.onlyBlockers ? "active" : ""}`}
                                onClick={() => model.setOnlyBlockers(!model.onlyBlockers)}
                            >
                                Only blockers
                            </button>
                            <button
                                type="button"
                                className={`secondary-button ${model.onlyEvidenceGaps ? "active" : ""}`}
                                onClick={() => model.setOnlyEvidenceGaps(!model.onlyEvidenceGaps)}
                            >
                                Only evidence gaps
                            </button>
                            <button
                                type="button"
                                className={`secondary-button ${model.showEvidence ? "active" : ""}`}
                                onClick={() => model.setShowEvidence(!model.showEvidence)}
                            >
                                Show evidence
                            </button>
                            <button
                                type="button"
                                className={`secondary-button ${model.showConflicts ? "active" : ""}`}
                                onClick={() => model.setShowConflicts(!model.showConflicts)}
                            >
                                Show conflicts
                            </button>
                            <button type="button" className="secondary-button" onClick={model.expandAll}>
                                Expand all
                            </button>
                            <button type="button" className="secondary-button" onClick={model.collapseAll}>
                                Collapse all
                            </button>
                            <button
                                type="button"
                                className="secondary-button"
                                onClick={() => flowInstance?.fitView({duration: 260, padding: 0.18})}
                            >
                                Reset view
                            </button>
                        </div>
                    </div>

                    <div className="graph-workspace">
                        <div className="graph-shell graph-canvas-shell">
                            <ReactFlow
                                nodes={flowNodes}
                                edges={flowEdges}
                                onInit={(instance) => setFlowInstance(instance)}
                                fitView
                                minZoom={0.2}
                                maxZoom={1.5}
                                nodesDraggable={false}
                                nodesConnectable={false}
                                elementsSelectable
                                panOnScroll
                                onNodeClick={(_, node) => model.selectNode(node.id)}
                                proOptions={{hideAttribution: true}}
                            >
                                <Controls showInteractive={false}/>
                                <Background
                                    variant={BackgroundVariant.Dots}
                                    gap={22}
                                    size={1}
                                    color="rgba(255, 255, 255, 0.05)"
                                />
                            </ReactFlow>
                        </div>

                        <aside className={`graph-inspector ${model.selectedId ? "is-open" : ""}`}>
                            {model.selectedArtifact ? (
                                <ArtifactInspector
                                    artifact={model.selectedArtifact}
                                    parentNode={model.selectedNode}
                                    onFocusParent={() => model.selectedStructuralId && model.focusNode(model.selectedStructuralId)}
                                    onClearFocus={model.clearFocus}
                                />
                            ) : model.selectedNode ? (
                                <NodeInspector
                                    node={model.selectedNode}
                                    evidence={model.index.evidenceByOwnerId.get(model.selectedNode.id) ?? []}
                                    conflicts={model.index.conflictsByOwnerId.get(model.selectedNode.id) ?? []}
                                    onClearFocus={model.clearFocus}
                                />
                            ) : (
                                <SummaryInspector
                                    graphSummary={graphSummary}
                                    hotspotGroups={hotspotGroups}
                                    onFocusNode={model.focusNode}
                                />
                            )}
                        </aside>
                    </div>
                </div>
            ) : (
                <EmptyState
                    title={graphEmptyTitle}
                    detail={graphEmptyDetail}
                />
            )}
        </Panel>
    );
}

function SummaryInspector({
                              graphSummary,
                              hotspotGroups,
                              onFocusNode
                          }: {
    graphSummary: GraphSummary;
    hotspotGroups: ReturnType<typeof buildHotspots>;
    onFocusNode: (nodeId: string) => void;
}) {
    return (
        <div className="graph-inspector-body">
            <div className="graph-inspector-header">
                <div>
                    <p className="eyebrow">Overview</p>
                    <h3>Explorer summary</h3>
                </div>
            </div>

            <div className="graph-summary-grid">
                <div className="graph-summary-card">
                    <span>Evidence anchors</span>
                    <strong>{graphSummary.evidenceCount}</strong>
                </div>
                <div className="graph-summary-card">
                    <span>Blocking conflicts</span>
                    <strong>{graphSummary.blockingConflictCount}</strong>
                </div>
                <div className="graph-summary-card">
                    <span>Warnings</span>
                    <strong>{graphSummary.warningConflictCount}</strong>
                </div>
                <div className="graph-summary-card">
                    <span>Uncovered nodes</span>
                    <strong>{graphSummary.uncoveredNodes}</strong>
                </div>
            </div>

            <HotspotSection title="Blocking hotspots" hotspots={hotspotGroups.blocking} onFocusNode={onFocusNode}/>
            <HotspotSection title="Warning hotspots" hotspots={hotspotGroups.warning} onFocusNode={onFocusNode}/>
            <HotspotSection title="Evidence gaps" hotspots={hotspotGroups.uncovered} onFocusNode={onFocusNode}/>
        </div>
    );
}

function NodeInspector({
                           node,
                           evidence,
                           conflicts,
                           onClearFocus
                       }: {
    node: GraphNode;
    evidence: ExplorerArtifact[];
    conflicts: ExplorerArtifact[];
    onClearFocus: () => void;
}) {
    const metadataItems = Object.entries(node.metadata)
        .filter(([, value]) => value !== undefined && value !== null && value !== "")
        .map(([label, value]) => ({
            label,
            value: formatMetadataValue(value)
        }));

    return (
        <div className="graph-inspector-body">
            <div className="graph-inspector-header">
                <div>
                    <p className="eyebrow">Selection</p>
                    <h3>{node.label}</h3>
                    <p className="graph-inspector-path">{node.path}</p>
                </div>
                <button type="button" className="secondary-button" onClick={onClearFocus}>
                    Clear focus
                </button>
            </div>

            <div className="graph-inspector-badges">
                <StatusBadge tone="info">{formatTypeLabel(node.type)}</StatusBadge>
                <StatusBadge tone={node.stats.blockingConflictCount ? "danger" : "neutral"}>
                    {node.stats.blockingConflictCount} blocking
                </StatusBadge>
                <StatusBadge tone={node.stats.warningConflictCount ? "warning" : "neutral"}>
                    {node.stats.warningConflictCount} warnings
                </StatusBadge>
                <StatusBadge tone={node.stats.evidenceCount ? "info" : "neutral"}>
                    {node.stats.evidenceCount} evidence
                </StatusBadge>
            </div>

                <KeyValueList
                    items={[
                    {label: "Interfaces", value: node.stats.boundedContextCount},
                    {label: "Data stores", value: node.stats.aggregateCount},
                    {label: "Operations", value: node.stats.commandCount},
                    {label: "Scenarios", value: node.stats.entityCount},
                    {label: "Integrations", value: node.stats.eventCount}
                ].filter((item) => Number(item.value) > 0)}
            />

            {metadataItems.length ? <KeyValueList items={metadataItems}/> : null}

            <div className="graph-inspector-section">
                <p className="eyebrow">Evidence</p>
                {evidence.length ? (
                    <div className="info-list">
                        {evidence.map((artifact) => (
                            <InfoListRow key={artifact.id} title={artifact.label} subtitle={artifact.subtitle}/>
                        ))}
                    </div>
                ) : (
                    <EmptyState
                        title="No direct evidence"
                        detail="This node currently has no extraction provenance attached directly to it."
                    />
                )}
            </div>

            <div className="graph-inspector-section">
                <p className="eyebrow">Conflicts</p>
                {conflicts.length ? (
                    <div className="info-list">
                        {conflicts.map((artifact) => (
                            <InfoListRow
                                key={artifact.id}
                                title={artifact.label}
                                subtitle={artifact.subtitle}
                                tone={artifact.severity === "danger" ? "danger" : "warning"}
                            />
                        ))}
                    </div>
                ) : (
                    <EmptyState
                        title="No direct conflicts"
                        detail="This node does not currently own any extraction conflict attachments."
                    />
                )}
            </div>
        </div>
    );
}

function ArtifactInspector({
                               artifact,
                               parentNode,
                               onFocusParent,
                               onClearFocus
                           }: {
    artifact: ExplorerArtifact;
    parentNode: GraphNode | null;
    onFocusParent: () => void;
    onClearFocus: () => void;
}) {
    return (
        <div className="graph-inspector-body">
            <div className="graph-inspector-header">
                <div>
                    <p className="eyebrow">{artifact.kind === "evidence" ? "Evidence" : "Conflict"}</p>
                    <h3>{artifact.label}</h3>
                    <p className="graph-inspector-path">{artifact.path}</p>
                </div>
                <button type="button" className="secondary-button" onClick={onClearFocus}>
                    Clear focus
                </button>
            </div>

            <div className="graph-inspector-badges">
                <StatusBadge tone={artifact.severity}>{artifact.kind}</StatusBadge>
                {parentNode ? (
                    <button type="button" className="secondary-button" onClick={onFocusParent}>
                        {parentNode.label}
                    </button>
                ) : null}
            </div>

            {artifact.provenance ? (
                <KeyValueList
                    items={[
                        {label: "File", value: artifact.provenance.file},
                        {label: "Symbol", value: artifact.provenance.symbol},
                        {label: "Range", value: `${artifact.provenance.startLine}-${artifact.provenance.endLine}`}
                    ]}
                />
            ) : null}

            {artifact.conflict ? (
                <KeyValueList
                    items={[
                        {label: "Message", value: artifact.conflict.message},
                        {label: "Preferred source", value: artifact.conflict.preferredSource},
                        {label: "Alternate source", value: artifact.conflict.alternateSource}
                    ]}
                />
            ) : null}
        </div>
    );
}

function HotspotSection({
                            title,
                            hotspots,
                            onFocusNode
                        }: {
    title: string;
    hotspots: ExplorerHotspot[];
    onFocusNode: (nodeId: string) => void;
}) {
    return (
        <div className="graph-inspector-section">
            <p className="eyebrow">{title}</p>
            {hotspots.length ? (
                <div className="graph-hotspot-list">
                    {hotspots.map((hotspot) => (
                        <button
                            key={hotspot.id}
                            type="button"
                            className="graph-hotspot-row"
                            onClick={() => onFocusNode(hotspot.id)}
                        >
                            <strong>{hotspot.label}</strong>
                            <span>{formatTypeLabel(hotspot.type)} · {hotspot.count}</span>
                        </button>
                    ))}
                </div>
            ) : (
                <EmptyState title={`No ${title.toLowerCase()}`}
                            detail="Nothing in the current graph matches this hotspot category."/>
            )}
        </div>
    );
}

function buildStructuralFlowNode(
    node: GraphNode,
    position: { x: number; y: number } | undefined,
    options: {
        dimmed: boolean;
        expanded: boolean;
        selectable: boolean;
        toggleExpanded: () => void;
    }
): Node<FlowLabelData> {
    const size = getStructuralNodeSize(node.type);
    const isExpandable = isExpandableNode(node.type);

    return {
        id: node.id,
        position: position ?? {x: 0, y: 0},
        style: {
            width: size.width,
            height: size.height
        },
        draggable: false,
        selectable: options.selectable,
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        className: [
            "graph-flow-node",
            `graph-flow-node--${node.type}`,
            `graph-flow-node--${getNodeVariant(node.type)}`,
            options.dimmed ? "is-dimmed" : ""
        ]
            .filter(Boolean)
            .join(" "),
        data: {
            label: (
                <div className="graph-card">
                    <div className="graph-card-head">
                        <span className="graph-card-kicker">{formatTypeLabel(node.type)}</span>
                        {isExpandable ? (
                            <button
                                type="button"
                                className="graph-card-toggle"
                                onClick={(event) => {
                                    event.stopPropagation();
                                    options.toggleExpanded();
                                }}
                                aria-label={options.expanded ? "Collapse node" : "Expand node"}
                            >
                                {options.expanded ? "−" : "+"}
                            </button>
                        ) : null}
                    </div>
                    <strong>{node.label}</strong>
                    <div className="graph-card-details">
                        {buildGraphChips(node).map((chip) => (
                            <span key={chip} className="graph-chip">
                {chip}
              </span>
                        ))}
                    </div>
                </div>
            )
        },
        type: "default"
    };
}

function buildStructuralFlowEdge(
    edge: GraphEdge,
    neighborhoodIds: Set<string>,
    selectedStructuralId: string | null
): Edge {
    const highlighted = !selectedStructuralId || (neighborhoodIds.has(edge.source) && neighborhoodIds.has(edge.target));
    const tone = EDGE_TONES[edge.label] ?? "#7fb3ff";

    return {
        id: edge.id,
        source: edge.source,
        target: edge.target,
        label: highlighted && selectedStructuralId ? edge.label : undefined,
        type: "smoothstep",
        markerEnd: {
            type: MarkerType.ArrowClosed,
            width: 18,
            height: 18,
            color: tone
        },
        style: {
            stroke: tone,
            strokeOpacity: highlighted ? 0.82 : 0.16,
            strokeWidth: highlighted ? 1.8 : 1.2
        },
        labelStyle: {
            fill: "#9fb2cb",
            fontSize: 10,
            fontWeight: 700
        }
    };
}

function buildArtifactFlowNodes(
    artifacts: ExplorerArtifact[],
    structuralNodes: Array<Node<FlowLabelData>>,
    selectedId: string | null
): Array<Node<FlowLabelData>> {
    const nodesById = new Map(structuralNodes.map((node) => [node.id, node]));
    const artifactOffsets = new Map<string, { evidence: number; conflict: number }>();

    return artifacts.map((artifact) => {
        const ownerNode = nodesById.get(artifact.ownerId);
        const ownerSize = ownerNode ? getNodeSizeForFlowNode(ownerNode) : {width: 240, height: 120};
        const offsets = artifactOffsets.get(artifact.ownerId) ?? {evidence: 0, conflict: 0};
        const isConflict = artifact.kind === "conflict";
        const index = isConflict ? offsets.conflict++ : offsets.evidence++;
        artifactOffsets.set(artifact.ownerId, offsets);

        return {
            id: artifact.id,
            position: ownerNode
                ? {
                    x: ownerNode.position.x + (isConflict ? -230 : ownerSize.width + 48),
                    y: ownerNode.position.y + 18 + index * 54
                }
                : {x: 0, y: 0},
            style: {
                width: 190,
                height: 46
            },
            draggable: false,
            selectable: true,
            className: [
                "graph-flow-node",
                "graph-flow-node--artifact",
                `graph-flow-node--artifact-${artifact.kind}`,
                selectedId === artifact.id ? "selected" : ""
            ].join(" "),
            data: {
                label: (
                    <div className="graph-artifact-pill">
                        <strong>{artifact.label}</strong>
                        <span>{artifact.subtitle}</span>
                    </div>
                )
            },
            type: "default"
        };
    });
}

function buildArtifactFlowEdges(
    artifacts: ExplorerArtifact[],
    selectedStructuralId: string | null
): Edge[] {
    return artifacts.map((artifact) => ({
        id: `artifact-edge:${artifact.id}`,
        source: artifact.ownerId,
        target: artifact.id,
        type: "smoothstep",
        markerEnd: {
            type: MarkerType.ArrowClosed,
            width: 16,
            height: 16,
            color: artifact.kind === "conflict" ? EDGE_TONES.BLOCKS : EDGE_TONES.EVIDENCED_BY
        },
        style: {
            stroke: artifact.kind === "conflict" ? EDGE_TONES.BLOCKS : EDGE_TONES.EVIDENCED_BY,
            strokeDasharray: "4 4",
            strokeOpacity: selectedStructuralId && selectedStructuralId !== artifact.ownerId ? 0.18 : 0.72,
            strokeWidth: 1.2
        }
    }));
}

function buildGraphChips(node: GraphNode) {
    const chips: string[] = [];

    if (node.type === "service") {
        chips.push(`${node.stats.boundedContextCount} interfaces`);
        chips.push(`${node.stats.aggregateCount} data stores`);
    } else if (node.type === "interface") {
        chips.push(`${node.stats.commandCount} operations`);
        chips.push(String(node.metadata.protocol ?? ""));
    } else if (node.type === "operation") {
        chips.push(`${String(node.metadata.method ?? "").toUpperCase()} ${String(node.path ?? "")}`.trim());
    } else if (node.type === "datastore") {
        chips.push(String(node.path ?? ""));
    } else if (node.type === "integration") {
        chips.push(String(node.metadata.direction ?? ""));
        chips.push(String(node.metadata.kind ?? ""));
    } else if (node.type === "workflow") {
        chips.push(String(node.metadata.summary ?? ""));
    } else if (node.type === "rule") {
        chips.push(String(node.metadata.statement ?? ""));
    } else if (node.type === "scenario") {
        chips.push(String(node.metadata.then ?? ""));
    } else if (node.type === "note") {
        chips.push(String(node.metadata.detail ?? ""));
    }

    if (node.stats.blockingConflictCount) {
        chips.push(`${node.stats.blockingConflictCount} blocking`);
    } else if (node.stats.warningConflictCount) {
        chips.push(`${node.stats.warningConflictCount} warnings`);
    }

    chips.push(node.stats.evidenceCount ? `${node.stats.evidenceCount} evidence` : "No evidence");

    return chips.filter(Boolean).slice(0, 4);
}

function getStructuralNodeSize(type: string): NodeSize {
    switch (type) {
        case "service":
            return {width: 320, height: 152};
        case "interface":
            return {width: 292, height: 148};
        case "workflow":
        case "integration":
            return {width: 260, height: 140};
        default:
            return {width: 220, height: 118};
    }
}

function getNodeSizeForFlowNode(node: Node<FlowLabelData>) {
    return {
        width: Number(node.style?.width ?? 220),
        height: Number(node.style?.height ?? 120)
    };
}

function getNodeVariant(type: string) {
    if (type === "service" || type === "interface") {
        return "summary";
    }

    if (type === "workflow" || type === "integration") {
        return "detail";
    }

    return "leaf";
}

function isExpandableNode(type: string) {
    return type === "service" || type === "interface";
}

function formatTypeLabel(type: string) {
    return type.replaceAll("-", " ");
}

function formatMetadataValue(value: unknown) {
    if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") {
        return String(value);
    }

    return JSON.stringify(value);
}
