import type {
    ExtractionConflict,
    ExtractionProvenance,
    ExtractionSnapshot,
    GraphEdge,
    GraphNode,
    GraphView
} from "../../types";

export const STRUCTURAL_NODE_TYPES = [
    "service",
    "interface",
    "operation",
    "datastore",
    "integration",
    "workflow",
    "rule",
    "scenario",
    "note"
] as const;

export type StructuralNodeType = (typeof STRUCTURAL_NODE_TYPES)[number];
export type ExplorerArtifactKind = "evidence" | "conflict";
export type ExplorerHotspotKind = "blocking" | "warning" | "uncovered";
export type ExplorerFilters = {
    onlyBlockers: boolean;
    onlyEvidenceGaps: boolean;
};

export type ExplorerArtifact = {
    id: string;
    ownerId: string;
    kind: ExplorerArtifactKind;
    label: string;
    subtitle: string;
    path: string;
    severity: "info" | "warning" | "danger";
    provenance?: ExtractionProvenance;
    conflict?: ExtractionConflict;
};

export type ExplorerSearchResult = {
    id: string;
    label: string;
    path: string;
    type: StructuralNodeType;
};

export type ExplorerHotspot = {
    id: string;
    label: string;
    path: string;
    type: StructuralNodeType;
    kind: ExplorerHotspotKind;
    count: number;
};

export type ExplorerIndex = {
    rootId: string | null;
    structuralNodes: GraphNode[];
    structuralEdges: GraphEdge[];
    nodeById: Map<string, GraphNode>;
    parentById: Map<string, string | null>;
    childrenById: Map<string, string[]>;
    artifactsById: Map<string, ExplorerArtifact>;
    evidenceByOwnerId: Map<string, ExplorerArtifact[]>;
    conflictsByOwnerId: Map<string, ExplorerArtifact[]>;
    allExpandableIds: string[];
};

export function createExplorerIndex(
    graph: GraphView | undefined,
    extraction?: ExtractionSnapshot
): ExplorerIndex {
    const provenance = extraction?.codebaseIr.provenance ?? [];
    const conflicts = extraction?.codebaseIr.conflicts ?? [];
    const evidenceSnapshot = extraction?.evidenceSnapshot;
    const evidenceNodesById = new Map((evidenceSnapshot?.nodes ?? []).map((node) => [node.id, node]));
    const structuralNodes = (graph?.nodes ?? []).filter((node): node is GraphNode & { type: StructuralNodeType } =>
        isStructuralNodeType(node.type)
    );
    const nodeById = new Map(structuralNodes.map((node) => [node.id, node]));
    const parentById = new Map<string, string | null>();
    const childrenById = new Map<string, string[]>();

    for (const node of structuralNodes) {
        const parentId = node.parentId ?? null;
        parentById.set(node.id, parentId);
        if (!parentId) {
            continue;
        }

        const children = childrenById.get(parentId) ?? [];
        children.push(node.id);
        childrenById.set(parentId, children);
    }

    const structuralEdges = (graph?.edges ?? []).filter(
        (edge) => nodeById.has(edge.source) && nodeById.has(edge.target)
    );
    const rootId = structuralNodes.find((node) => node.type === "service")?.id ?? null;
    const exactPathLookup = new Map(structuralNodes.map((node) => [node.path, node.id]));
    const pathNodes = [...structuralNodes]
        .sort((left, right) => right.path.length - left.path.length)
        .map((node) => ({id: node.id, path: node.path}));
    const subjectOwnerLookup = buildSubjectOwnerLookup(structuralNodes, extraction);
    const evidenceByOwnerId = new Map<string, ExplorerArtifact[]>();
    const conflictsByOwnerId = new Map<string, ExplorerArtifact[]>();
    const artifactsById = new Map<string, ExplorerArtifact>();

    if (evidenceSnapshot) {
        evidenceSnapshot.refs.forEach((ref, index) => {
            const evidenceNode = evidenceNodesById.get(ref.evidenceNodeId) ?? evidenceNodesById.get(ref.ownerId);
            const ownerId = resolveEvidenceOwnerId(
                evidenceNode?.path ?? ref.file,
                rootId,
                exactPathLookup,
                pathNodes
            );
            if (!ownerId) {
                return;
            }

            const artifact: ExplorerArtifact = {
                id: `evidence:${ownerId}:${index}`,
                ownerId,
                kind: "evidence",
                label: evidenceNode?.label ?? ref.ownerId,
                subtitle: `${ref.file}:${ref.startLine}-${ref.endLine}`,
                path: evidenceNode?.path ?? ref.file,
                severity: "info",
                provenance: {
                    source: evidenceNode?.kind,
                    subjectId: ref.ownerId,
                    file: ref.file,
                    symbol: evidenceNode?.label ?? ref.ownerId,
                    startLine: ref.startLine,
                    endLine: ref.endLine
                }
            };
            const entries = evidenceByOwnerId.get(ownerId) ?? [];
            entries.push(artifact);
            evidenceByOwnerId.set(ownerId, entries);
            artifactsById.set(artifact.id, artifact);
        });

        evidenceSnapshot.conflicts.forEach((conflict, index) => {
            const evidenceNode = conflict.evidenceNodeIds
                .map((id) => evidenceNodesById.get(id))
                .find((node) => Boolean(node));
            const ownerId = resolveEvidenceOwnerId(
                evidenceNode?.path ?? conflict.summary,
                rootId,
                exactPathLookup,
                pathNodes
            );
            if (!ownerId) {
                return;
            }

            const artifact: ExplorerArtifact = {
                id: `conflict:${ownerId}:${index}`,
                ownerId,
                kind: "conflict",
                label: evidenceNode?.label ?? conflict.summary,
                subtitle: conflict.summary,
                path: evidenceNode?.path ?? conflict.summary,
                severity: isFatalSeverity(conflict.severity) ? "danger" : "warning",
                conflict: {
                    subjectId: evidenceNode?.id,
                    domain: evidenceNode?.kind,
                    preferredSource: "evidence",
                    alternateSource: "semantic",
                    message: conflict.summary,
                    fatal: isFatalSeverity(conflict.severity)
                }
            };
            const entries = conflictsByOwnerId.get(ownerId) ?? [];
            entries.push(artifact);
            conflictsByOwnerId.set(ownerId, entries);
            artifactsById.set(artifact.id, artifact);
        });
    } else {
        provenance.forEach((entry, index) => {
            const ownerPath = entry.subjectId ?? entry.file;
            const ownerId =
                resolveSubjectOwnerId(entry.subjectId, rootId, subjectOwnerLookup) ??
                resolveOwnerId(ownerPath, rootId, exactPathLookup, pathNodes);
            if (!ownerId) {
                return;
            }

            const artifact: ExplorerArtifact = {
                id: `evidence:${ownerId}:${index}`,
                ownerId,
                kind: "evidence",
                label: entry.symbol,
                subtitle: `${entry.file}:${entry.startLine}-${entry.endLine}`,
                path: ownerPath,
                severity: "info",
                provenance: entry
            };
            const entries = evidenceByOwnerId.get(ownerId) ?? [];
            entries.push(artifact);
            evidenceByOwnerId.set(ownerId, entries);
            artifactsById.set(artifact.id, artifact);
        });

        conflicts.forEach((conflict, index) => {
            const ownerPath = conflict.subjectId ?? conflict.domain ?? conflict.message;
            const ownerId =
                resolveSubjectOwnerId(conflict.subjectId, rootId, subjectOwnerLookup) ??
                resolveOwnerId(ownerPath, rootId, exactPathLookup, pathNodes);
            if (!ownerId) {
                return;
            }

            const artifact: ExplorerArtifact = {
                id: `conflict:${ownerId}:${index}`,
                ownerId,
                kind: "conflict",
                label: ownerPath,
                subtitle: conflict.message,
                path: ownerPath,
                severity: conflict.fatal ? "danger" : "warning",
                conflict
            };
            const entries = conflictsByOwnerId.get(ownerId) ?? [];
            entries.push(artifact);
            conflictsByOwnerId.set(ownerId, entries);
            artifactsById.set(artifact.id, artifact);
        });
    }

    return {
        rootId,
        structuralNodes,
        structuralEdges,
        nodeById,
        parentById,
        childrenById,
        artifactsById,
        evidenceByOwnerId,
        conflictsByOwnerId,
        allExpandableIds: structuralNodes
            .filter((node) => (childrenById.get(node.id) ?? []).length > 0 && node.type !== "service")
            .map((node) => node.id)
    };
}

function buildSubjectOwnerLookup(structuralNodes: GraphNode[], extraction?: ExtractionSnapshot) {
    const lookup = new Map<string, string>();
    if (!extraction) {
        return lookup;
    }

    const aggregateOwners = new Map<string, string>();
    const entityOwners = new Map<string, string>();
    const commandOwners = new Map<string, string>();

    for (const node of structuralNodes) {
        if (node.type === "aggregate") {
            aggregateOwners.set(canonicalTypeName(node.label), node.id);
        } else if (node.type === "entity") {
            entityOwners.set(canonicalTypeName(node.label), node.id);
        } else if (node.type === "command") {
            const method = typeof node.metadata.method === "string" ? node.metadata.method : "";
            const httpPath = typeof node.metadata.httpPath === "string" ? node.metadata.httpPath : "";
            if (method && httpPath) {
                commandOwners.set(endpointKey(method, httpPath), node.id);
            }
        }
    }

    for (const entity of extraction.codebaseIr.jpaEntities) {
        const entityCanonical = canonicalTypeName(simpleName(entity.typeId));
        const ownerId = entityOwners.get(entityCanonical) ?? aggregateOwners.get(entityCanonical);
        if (!ownerId) {
            continue;
        }
        lookup.set(entity.id, ownerId);
        lookup.set(entity.typeId, ownerId);
        for (const idFieldId of entity.idFieldIds) {
            lookup.set(idFieldId, ownerId);
        }
        for (const attribute of entity.attributes) {
            lookup.set(attribute.id, ownerId);
            lookup.set(attribute.fieldId, ownerId);
        }
    }

    for (const type of extraction.codebaseIr.types) {
        const defaultOwnerId = entityOwners.get(canonicalTypeName(type.simpleName)) ?? aggregateOwners.get(canonicalTypeName(type.simpleName));
        if (defaultOwnerId) {
            lookup.set(type.id, defaultOwnerId);
        }
        for (const field of type.fields) {
            if (defaultOwnerId && !lookup.has(field.id)) {
                lookup.set(field.id, defaultOwnerId);
            }
        }
        for (const method of type.methods) {
            let ownerId = defaultOwnerId;
            const ownerType = ownerTypeId(method.id);
            if (ownerType) {
                ownerId = aggregateOwners.get(canonicalTypeName(simpleName(ownerType))) ?? ownerId;
            }
            if (ownerId && !lookup.has(method.id)) {
                lookup.set(method.id, ownerId);
            }
        }
    }

    for (const endpoint of extraction.codebaseIr.endpoints) {
        let ownerId = commandOwners.get(endpointKey(endpoint.httpMethod ?? "", endpoint.fullPath ?? ""));
        if (!ownerId) {
            const ownerType = ownerTypeId(endpoint.methodId);
            if (ownerType) {
                ownerId = aggregateOwners.get(canonicalTypeName(simpleName(ownerType)));
            }
        }
        if (!ownerId) {
            continue;
        }
        lookup.set(endpoint.id, ownerId);
        lookup.set(endpoint.methodId, ownerId);
    }

    for (const bean of extraction.codebaseIr.beans) {
        const ownerId = aggregateOwners.get(canonicalTypeName(simpleName(bean.typeId)));
        if (!ownerId) {
            continue;
        }
        lookup.set(bean.id, ownerId);
        lookup.set(bean.typeId, ownerId);
    }

    return lookup;
}

function resolveSubjectOwnerId(subjectId: string | undefined, rootId: string | null, lookup: Map<string, string>) {
    if (!subjectId) {
        return rootId;
    }
    return lookup.get(subjectId) ?? rootId;
}

function endpointKey(method: string, path: string) {
    return `${method.toUpperCase()} ${path}`;
}

function ownerTypeId(memberId: string) {
    const marker = memberId.indexOf("#");
    return marker < 0 ? null : memberId.slice(0, marker);
}

function simpleName(qualifiedName: string) {
    const marker = qualifiedName.lastIndexOf(".");
    return marker < 0 ? qualifiedName : qualifiedName.slice(marker + 1);
}

function canonicalTypeName(typeName: string) {
    let value = typeName;
    let previous = "";

    while (value !== previous) {
        previous = value;
        for (const suffix of ["Controller", "Service", "Facade", "Impl", "Resource", "Entity"]) {
            if (value.endsWith(suffix) && value.length > suffix.length) {
                value = value.slice(0, -suffix.length);
                break;
            }
        }
    }

    return value
        .replace(/([a-z0-9])([A-Z])/g, "$1_$2")
        .replace(/[-.]/g, "_")
        .toLowerCase();
}

export function isStructuralNodeType(type: string): type is StructuralNodeType {
    return STRUCTURAL_NODE_TYPES.includes(type as StructuralNodeType);
}

export function expandAncestors(index: ExplorerIndex, nodeId: string, expandedIds: Set<string>) {
    const next = new Set(expandedIds);
    let currentId = index.parentById.get(nodeId) ?? null;

    while (currentId) {
        if (currentId !== index.rootId) {
            next.add(currentId);
        }
        currentId = index.parentById.get(currentId) ?? null;
    }

    return next;
}

export function deriveVisibleStructuralIds(
    index: ExplorerIndex,
    expandedIds: Set<string>,
    filters: ExplorerFilters
) {
    if (!index.rootId) {
        return [] as string[];
    }

    if (filters.onlyBlockers || filters.onlyEvidenceGaps) {
        const filtered = new Set<string>();

        for (const node of index.structuralNodes) {
            const matchesBlocker = filters.onlyBlockers && node.stats.blockingConflictCount > 0;
            const matchesEvidenceGap = filters.onlyEvidenceGaps && node.type !== "service" && node.stats.evidenceCount === 0;

            if (!matchesBlocker && !matchesEvidenceGap) {
                continue;
            }

            addWithAncestors(filtered, index, node.id);
        }

        if (filtered.size) {
            return [...filtered];
        }
    }

    const visible = new Set<string>();

    function visit(nodeId: string) {
        visible.add(nodeId);
        const shouldExpand = nodeId === index.rootId || expandedIds.has(nodeId);

        if (!shouldExpand) {
            return;
        }

        for (const childId of index.childrenById.get(nodeId) ?? []) {
            visit(childId);
        }
    }

    visit(index.rootId);
    return [...visible];
}

export function deriveNeighborhood(index: ExplorerIndex, nodeId: string | null) {
    if (!nodeId || !index.nodeById.has(nodeId)) {
        return new Set<string>();
    }

    const neighborhood = new Set<string>([nodeId]);
    let currentId = index.parentById.get(nodeId) ?? null;

    while (currentId) {
        neighborhood.add(currentId);
        currentId = index.parentById.get(currentId) ?? null;
    }

    for (const childId of index.childrenById.get(nodeId) ?? []) {
        neighborhood.add(childId);
    }

    return neighborhood;
}

export function deriveMaterializedArtifacts(
    index: ExplorerIndex,
    visibleStructuralIds: Iterable<string>,
    selectedStructuralId: string | null,
    showEvidence: boolean,
    showConflicts: boolean
) {
    const ownerIds = selectedStructuralId ? [selectedStructuralId] : [...visibleStructuralIds];
    const artifacts: ExplorerArtifact[] = [];

    for (const ownerId of ownerIds) {
        if (showEvidence) {
            artifacts.push(...(index.evidenceByOwnerId.get(ownerId) ?? []));
        }
        if (showConflicts) {
            artifacts.push(...(index.conflictsByOwnerId.get(ownerId) ?? []));
        }
    }

    return artifacts;
}

export function buildSearchResults(index: ExplorerIndex, query: string) {
    const normalizedQuery = query.trim().toLowerCase();
    if (!normalizedQuery) {
        return [] as ExplorerSearchResult[];
    }

    return index.structuralNodes
        .map((node) => ({
            node,
            score: scoreSearchResult(node, normalizedQuery)
        }))
        .filter((entry) => entry.score > 0)
        .sort((left, right) => right.score - left.score || left.node.label.localeCompare(right.node.label))
        .slice(0, 8)
        .map((entry) => ({
            id: entry.node.id,
            label: entry.node.label,
            path: entry.node.path,
            type: entry.node.type as StructuralNodeType
        }));
}

export function buildHotspots(index: ExplorerIndex) {
    return {
        blocking: buildHotspotList(index, "blocking", (node) => node.stats.blockingConflictCount),
        warning: buildHotspotList(index, "warning", (node) => node.stats.warningConflictCount),
        uncovered: buildHotspotList(index, "uncovered", (node) => (node.type !== "service" && node.stats.evidenceCount === 0 ? 1 : 0))
    };
}

function buildHotspotList(
    index: ExplorerIndex,
    kind: ExplorerHotspotKind,
    countForNode: (node: GraphNode) => number
) {
    return index.structuralNodes
        .filter((node) => node.type !== "service")
        .map((node) => ({
            id: node.id,
            label: node.label,
            path: node.path,
            type: node.type as StructuralNodeType,
            kind,
            count: countForNode(node)
        }))
        .filter((node) => node.count > 0)
        .sort((left, right) => right.count - left.count || left.label.localeCompare(right.label))
        .slice(0, 6);
}

function addWithAncestors(target: Set<string>, index: ExplorerIndex, nodeId: string) {
    let currentId: string | null = nodeId;
    while (currentId) {
        target.add(currentId);
        currentId = index.parentById.get(currentId) ?? null;
    }
}

function resolveEvidenceOwnerId(
    artifactPath: string | undefined,
    rootId: string | null,
    exactPathLookup: Map<string, string>,
    pathNodes: Array<{ id: string; path: string }>
) {
    return resolveOwnerId(artifactPath, rootId, exactPathLookup, pathNodes);
}

function resolveOwnerId(
    artifactPath: string | undefined,
    rootId: string | null,
    exactPathLookup: Map<string, string>,
    pathNodes: Array<{ id: string; path: string }>
) {
    if (!rootId) {
        return null;
    }

    if (!artifactPath) {
        return rootId;
    }

    const exactMatch = exactPathLookup.get(artifactPath);
    if (exactMatch) {
        return exactMatch;
    }

    for (const node of pathNodes) {
        if (matchesPathPrefix(artifactPath, node.path)) {
            return node.id;
        }
    }

    return rootId;
}

function matchesPathPrefix(candidate: string, nodePath: string) {
    if (!candidate.startsWith(nodePath)) {
        return false;
    }

    if (candidate.length === nodePath.length) {
        return true;
    }

    const separator = candidate.charAt(nodePath.length);
    return separator === "/" || separator === ":" || separator === "#";
}

function isFatalSeverity(value: string | undefined) {
    if (!value) {
        return false;
    }
    const normalized = value.toLowerCase();
    return normalized === "fatal" || normalized === "error" || normalized === "blocking";
}

function scoreSearchResult(node: GraphNode, normalizedQuery: string) {
    const label = node.label.toLowerCase();
    const path = node.path.toLowerCase();
    const metadata = JSON.stringify(node.metadata).toLowerCase();
    const type = node.type.toLowerCase();

    if (label === normalizedQuery) {
        return 100;
    }

    let score = 0;
    if (label.startsWith(normalizedQuery)) {
        score += 80;
    } else if (label.includes(normalizedQuery)) {
        score += 60;
    }

    if (path.includes(normalizedQuery)) {
        score += 30;
    }

    if (metadata.includes(normalizedQuery)) {
        score += 20;
    }

    if (type.includes(normalizedQuery)) {
        score += 10;
    }

    return score;
}
