import type { PipelineStage, StageSummary } from "../types";
import { Panel, StageStatusBadge } from "./primitives";

export function StageNavigator({
  stages,
  activeStage,
  onSelectStage
}: {
  stages: StageSummary[];
  activeStage: PipelineStage;
  onSelectStage: (stage: PipelineStage) => void;
}) {
  return (
    <Panel className="stage-nav-panel">
      <div className="stage-nav">
        {stages.map((stage, index) => (
          <button
            key={stage.id}
            type="button"
            className={`stage-nav-button ${stage.id === activeStage ? "active" : ""}`}
            onClick={() => onSelectStage(stage.id)}
          >
            <span className="stage-nav-index">{String(index + 1).padStart(2, "0")}</span>
            <div className="stage-nav-copy">
              <div className="stage-nav-head">
                <strong>{stage.label}</strong>
                <StageStatusBadge status={stage.status} />
              </div>
              <small>{stage.subtitle}</small>
              <p>{stage.detail}</p>
            </div>
          </button>
        ))}
      </div>
    </Panel>
  );
}
