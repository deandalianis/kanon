import { Background, Controls, MiniMap, ReactFlow, type Edge, type Node } from "@xyflow/react";
import type { ExtractionConflict, ExtractionProvenance } from "../../../types";
import type { GraphSummary } from "../types";
import { EmptyState, InfoListRow, Panel, SectionHeader, StatusBadge } from "./primitives";

export function GraphStage({
  hasExtractionRun,
  graphNodes,
  graphEdges,
  graphSummary,
  evidence,
  conflicts,
  onRefreshExtraction,
  extractionPending
}: {
  hasExtractionRun: boolean;
  graphNodes: Node[];
  graphEdges: Edge[];
  graphSummary: GraphSummary;
  evidence: ExtractionProvenance[];
  conflicts: ExtractionConflict[];
  onRefreshExtraction: () => void;
  extractionPending: boolean;
}) {
  return (
    <div className="stage-grid">
      <div className="stage-main">
        <Panel>
          <SectionHeader
            eyebrow="Graph"
            title="Lineage graph"
            description="Inspect the lineage graph projected from the current spec and linked extraction evidence."
            badge={
              <StatusBadge tone={graphSummary.blockingEdges ? "danger" : graphSummary.nodes ? "info" : "neutral"}>
                {graphSummary.nodes ? `${graphSummary.nodes} nodes` : "not loaded"}
              </StatusBadge>
            }
            actions={
              <button
                type="button"
                className="secondary-button"
                onClick={onRefreshExtraction}
                disabled={extractionPending}
              >
                {extractionPending ? "Refreshing..." : "Refresh extraction"}
              </button>
            }
          />

          {!hasExtractionRun ? (
            <EmptyState
              title="Graph unavailable"
              detail="Run extraction before opening the lineage graph so evidence anchors and conflicts are available."
              action={
                <button
                  type="button"
                  className="primary-button"
                  onClick={onRefreshExtraction}
                  disabled={extractionPending}
                >
                  {extractionPending ? "Refreshing..." : "Run extraction"}
                </button>
              }
            />
          ) : graphNodes.length ? (
            <div className="graph-shell">
              <ReactFlow
                nodes={graphNodes}
                edges={graphEdges}
                fitView
                minZoom={0.2}
                maxZoom={1.4}
                attributionPosition="bottom-left"
                proOptions={{ hideAttribution: true }}
              >
                <MiniMap pannable zoomable />
                <Controls />
                <Background gap={22} size={1} />
              </ReactFlow>
            </div>
          ) : (
            <EmptyState
              title="No lineage graph loaded"
              detail="Open the graph stage after extraction to build the lineage projection for this workspace."
            />
          )}
        </Panel>
      </div>

      <div className="stage-side">
        <Panel>
          <SectionHeader eyebrow="Legend" title="Graph composition" />
          <div className="legend-list">
            <InfoListRow title="Services" subtitle={graphSummary.serviceNodes} />
            <InfoListRow title="Bounded contexts" subtitle={graphSummary.boundedContextNodes} />
            <InfoListRow title="Aggregates" subtitle={graphSummary.aggregateNodes} />
            <InfoListRow title="Commands" subtitle={graphSummary.commandNodes} />
            <InfoListRow title="Entities" subtitle={graphSummary.entityNodes} />
            <InfoListRow title="Events" subtitle={graphSummary.eventNodes} />
            <InfoListRow title="Evidence anchors" subtitle={graphSummary.evidenceNodes} />
            <InfoListRow title="Conflicts" subtitle={graphSummary.conflictNodes} />
            <InfoListRow title="Blocking links" subtitle={graphSummary.blockingEdges} tone={graphSummary.blockingEdges ? "danger" : "neutral"} />
          </div>
        </Panel>

        <Panel>
          <SectionHeader eyebrow="Evidence" title="Lineage anchors" />
          {evidence.length ? (
            <div className="info-list">
              {evidence.map((entry) => (
                <InfoListRow
                  key={`${entry.file}-${entry.startLine}-${entry.path}`}
                  title={entry.symbol}
                  subtitle={`${entry.file}:${entry.startLine}-${entry.endLine}`}
                />
              ))}
            </div>
          ) : (
            <EmptyState
              title="No evidence anchors"
              detail="Extraction evidence will appear here once the workspace has been refreshed."
            />
          )}
        </Panel>

        <Panel>
          <SectionHeader eyebrow="Risks" title="Graph-linked conflicts" />
          {conflicts.length ? (
            <div className="info-list">
              {conflicts.map((conflict, index) => (
                <InfoListRow
                  key={`${conflict.path}-${index}`}
                  title={conflict.path}
                  subtitle={conflict.message}
                  tone={conflict.fatal ? "danger" : "warning"}
                />
              ))}
            </div>
          ) : (
            <EmptyState
              title="No graph risks"
              detail="No extraction conflicts are currently linked into the lineage view."
            />
          )}
        </Panel>
      </div>
    </div>
  );
}
