import type {RunSummary, WorkspaceRef} from "../../../types";
import type {NextAction, WorkbenchNotice} from "../types";
import {countEnabledCapabilities, formatDate, summarizeBootstrapProgress, summarizeBootstrapRun} from "../utils";
import {MetricTile, Panel, ProgressMeter} from "./primitives";

export function WorkbenchHeader({
                                    project,
                                    latestRun,
                                    latestBootstrapRun,
                                    currentBlocker,
                                    nextAction,
                                    onNextAction
                                }: {
    project: WorkspaceRef;
    latestRun: RunSummary | null;
    latestBootstrapRun: RunSummary | null;
    currentBlocker: WorkbenchNotice;
    nextAction: NextAction;
    onNextAction: () => void;
}) {
    const bootstrapProgress = summarizeBootstrapProgress(latestBootstrapRun);

    return (
        <Panel className="briefing-panel">
            <div className="briefing-grid">
                <div className="briefing-main">
                    <p className="eyebrow">Workspace briefing</p>
                    <h2>{project.name}</h2>
                    <p className="briefing-summary">
                        {project.profile.serviceName} | {project.profile.framework} | {countEnabledCapabilities(project.profile.capabilities)}{" "}
                        capabilities enabled
                    </p>
                    <dl className="briefing-list">
                        <div className="briefing-row">
                            <dt>Source</dt>
                            <dd className="mono-text">{project.sourcePath}</dd>
                        </div>
                        <div className="briefing-row">
                            <dt>Workspace</dt>
                            <dd className="mono-text">{project.workspacePath}</dd>
                        </div>
                        <div className="briefing-row">
                            <dt>Base package</dt>
                            <dd className="mono-text">{project.profile.basePackage}</dd>
                        </div>
                        <div className="briefing-row">
                            <dt>Latest run</dt>
                            <dd>{latestRun ? `${latestRun.kind} / ${latestRun.status} at ${formatDate(latestRun.startedAt)}` : "No runs yet"}</dd>
                        </div>
                        <div className="briefing-row">
                            <dt>Bootstrap</dt>
                            <dd>
                                {latestBootstrapRun
                                    ? `${summarizeBootstrapRun(latestBootstrapRun)} at ${formatDate(latestBootstrapRun.startedAt)}`
                                    : "No bootstrap run yet"}
                            </dd>
                        </div>
                    </dl>
                </div>

                <div className="briefing-metrics">
                    <MetricTile
                        label="Current blocker"
                        value={currentBlocker.label}
                        detail={currentBlocker.detail}
                        tone={currentBlocker.tone}
                    />
                    <MetricTile
                        label="Recommended next action"
                        value={nextAction.label}
                        detail={nextAction.detail}
                        tone={nextAction.tone}
                        action={
                            <button type="button" className="secondary-button" onClick={onNextAction}>
                                {nextAction.label}
                            </button>
                        }
                    />
                    <MetricTile
                        label="Bootstrap progress"
                        value={bootstrapProgress.headline}
                        detail={bootstrapProgress.detail}
                        tone={bootstrapProgress.tone}
                        action={
                            <ProgressMeter
                                value={bootstrapProgress.progressValue}
                                max={bootstrapProgress.progressMax}
                                tone={bootstrapProgress.tone}
                                animated={bootstrapProgress.animated}
                                label={bootstrapProgress.progressLabel}
                            />
                        }
                    />
                </div>
            </div>
        </Panel>
    );
}
