import { useEffect, useMemo, useState } from "react";
import type { ExtractionResult, GraphView } from "../../types";
import {
  buildHotspots,
  buildSearchResults,
  createExplorerIndex,
  deriveMaterializedArtifacts,
  deriveNeighborhood,
  deriveVisibleStructuralIds,
  expandAncestors
} from "./graphExplorerModel";

export function useGraphExplorerModel(graph: GraphView | undefined, extraction: ExtractionResult | undefined) {
  const index = useMemo(
    () => createExplorerIndex(graph, extraction?.provenance, extraction?.conflicts),
    [graph, extraction?.provenance, extraction?.conflicts]
  );
  const [expandedIds, setExpandedIds] = useState<Set<string>>(() => new Set<string>());
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState("");
  const [showEvidence, setShowEvidence] = useState(false);
  const [showConflicts, setShowConflicts] = useState(false);
  const [onlyBlockers, setOnlyBlockers] = useState(false);
  const [onlyEvidenceGaps, setOnlyEvidenceGaps] = useState(false);
  const [focusRequest, setFocusRequest] = useState({ id: null as string | null, token: 0 });

  useEffect(() => {
    setExpandedIds(new Set<string>());
    setSelectedId(null);
    setSearchQuery("");
    setShowEvidence(false);
    setShowConflicts(false);
    setOnlyBlockers(false);
    setOnlyEvidenceGaps(false);
    setFocusRequest({ id: null, token: 0 });
  }, [index.rootId, graph?.nodes.length, graph?.edges.length]);

  const visibleStructuralIds = useMemo(
    () => deriveVisibleStructuralIds(index, expandedIds, { onlyBlockers, onlyEvidenceGaps }),
    [expandedIds, index, onlyBlockers, onlyEvidenceGaps]
  );
  const visibleStructuralIdSet = useMemo(() => new Set(visibleStructuralIds), [visibleStructuralIds]);
  const visibleStructuralNodes = useMemo(
    () =>
      visibleStructuralIds
        .map((nodeId) => index.nodeById.get(nodeId))
        .filter((node): node is NonNullable<typeof node> => Boolean(node)),
    [index.nodeById, visibleStructuralIds]
  );
  const visibleStructuralEdges = useMemo(
    () => index.structuralEdges.filter(
      (edge) => visibleStructuralIdSet.has(edge.source) && visibleStructuralIdSet.has(edge.target)
    ),
    [index.structuralEdges, visibleStructuralIdSet]
  );
  const selectedArtifact = selectedId ? index.artifactsById.get(selectedId) ?? null : null;
  const selectedStructuralId =
    selectedArtifact?.ownerId ?? (selectedId && index.nodeById.has(selectedId) ? selectedId : null);
  const neighborhoodIds = useMemo(
    () => deriveNeighborhood(index, selectedStructuralId),
    [index, selectedStructuralId]
  );
  const materializedArtifacts = useMemo(
    () => deriveMaterializedArtifacts(index, visibleStructuralIds, selectedStructuralId, showEvidence, showConflicts),
    [index, selectedStructuralId, showConflicts, showEvidence, visibleStructuralIds]
  );
  const visibleArtifactIds = useMemo(
    () => new Set(materializedArtifacts.map((artifact) => artifact.id)),
    [materializedArtifacts]
  );
  const searchResults = useMemo(() => buildSearchResults(index, searchQuery), [index, searchQuery]);
  const hotspots = useMemo(() => buildHotspots(index), [index]);
  const selectedNode = selectedStructuralId ? index.nodeById.get(selectedStructuralId) ?? null : null;

  useEffect(() => {
    if (!selectedId) {
      return;
    }

    if (index.nodeById.has(selectedId) && visibleStructuralIdSet.has(selectedId)) {
      return;
    }

    if (visibleArtifactIds.has(selectedId)) {
      return;
    }

    setSelectedId(null);
  }, [index.nodeById, selectedId, visibleArtifactIds, visibleStructuralIdSet]);

  function toggleExpanded(nodeId: string) {
    setExpandedIds((current) => {
      const next = new Set(current);
      if (next.has(nodeId)) {
        next.delete(nodeId);
      } else {
        next.add(nodeId);
      }
      return next;
    });
  }

  function selectNode(nodeId: string | null) {
    setSelectedId(nodeId);
  }

  function clearFocus() {
    setSelectedId(null);
  }

  function selectSearchResult(nodeId: string) {
    setExpandedIds((current) => expandAncestors(index, nodeId, current));
    setSelectedId(nodeId);
    setFocusRequest((current) => ({ id: nodeId, token: current.token + 1 }));
  }

  function focusNode(nodeId: string) {
    setExpandedIds((current) => expandAncestors(index, nodeId, current));
    setSelectedId(nodeId);
    setFocusRequest((current) => ({ id: nodeId, token: current.token + 1 }));
  }

  function expandAll() {
    setExpandedIds(new Set(index.allExpandableIds));
  }

  function collapseAll() {
    setExpandedIds(new Set<string>());
  }

  return {
    searchQuery,
    setSearchQuery,
    searchResults,
    showEvidence,
    setShowEvidence,
    showConflicts,
    setShowConflicts,
    onlyBlockers,
    setOnlyBlockers,
    onlyEvidenceGaps,
    setOnlyEvidenceGaps,
    selectedId,
    selectedNode,
    selectedArtifact,
    selectedStructuralId,
    visibleStructuralNodes,
    visibleStructuralEdges,
    visibleStructuralIdSet,
    materializedArtifacts,
    neighborhoodIds,
    hotspots,
    focusRequest,
    index,
    isExpanded: (nodeId: string) => expandedIds.has(nodeId),
    isDimmed: (nodeId: string) => Boolean(selectedStructuralId && !neighborhoodIds.has(nodeId)),
    toggleExpanded,
    selectNode,
    clearFocus,
    selectSearchResult,
    focusNode,
    expandAll,
    collapseAll
  };
}
