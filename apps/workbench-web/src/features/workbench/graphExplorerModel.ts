import type {
  ExtractionConflict,
  ExtractionProvenance,
  GraphEdge,
  GraphNode,
  GraphView
} from "../../types";

export const STRUCTURAL_NODE_TYPES = [
  "service",
  "bounded-context",
  "aggregate",
  "command",
  "entity",
  "event"
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
  provenance: ExtractionProvenance[] = [],
  conflicts: ExtractionConflict[] = []
): ExplorerIndex {
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
    .map((node) => ({ id: node.id, path: node.path }));
  const evidenceByOwnerId = new Map<string, ExplorerArtifact[]>();
  const conflictsByOwnerId = new Map<string, ExplorerArtifact[]>();
  const artifactsById = new Map<string, ExplorerArtifact>();

  provenance.forEach((entry, index) => {
    const ownerId = resolveOwnerId(entry.path, rootId, exactPathLookup, pathNodes);
    if (!ownerId) {
      return;
    }

    const artifact: ExplorerArtifact = {
      id: `evidence:${ownerId}:${index}`,
      ownerId,
      kind: "evidence",
      label: entry.symbol,
      subtitle: `${entry.file}:${entry.startLine}-${entry.endLine}`,
      path: entry.path,
      severity: "info",
      provenance: entry
    };
    const entries = evidenceByOwnerId.get(ownerId) ?? [];
    entries.push(artifact);
    evidenceByOwnerId.set(ownerId, entries);
    artifactsById.set(artifact.id, artifact);
  });

  conflicts.forEach((conflict, index) => {
    const ownerId = resolveOwnerId(conflict.path, rootId, exactPathLookup, pathNodes);
    if (!ownerId) {
      return;
    }

    const artifact: ExplorerArtifact = {
      id: `conflict:${ownerId}:${index}`,
      ownerId,
      kind: "conflict",
      label: conflict.path,
      subtitle: conflict.message,
      path: conflict.path,
      severity: conflict.fatal ? "danger" : "warning",
      conflict
    };
    const entries = conflictsByOwnerId.get(ownerId) ?? [];
    entries.push(artifact);
    conflictsByOwnerId.set(ownerId, entries);
    artifactsById.set(artifact.id, artifact);
  });

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
