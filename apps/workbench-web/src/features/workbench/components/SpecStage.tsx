import {DiffEditor, Editor} from "@monaco-editor/react";
import type {BootstrapRunMetadata, RunSummary, SpecFile} from "../../../types";
import {
    type BddAggregate,
    SPEC_STAGE_LABELS,
    type SpecStageKey
} from "../types";
import {
    formatDate,
    humanizeRunKind,
    parseSynthesisRunMetadata,
    summarizeBootstrapProgress,
    summarizeBootstrapRun,
    toneForRunStatus
} from "../utils";
import {BddScenariosViewer} from "./BddScenariosViewer";
import {EmptyState, InfoListRow, KeyValueList, Panel, ProgressMeter, SectionHeader, StatusBadge} from "./primitives";

export function SpecStage({
                              specStage,
                              onSelectSpecStage,
                              currentSpec,
                              currentSpecLoading,
                              approvedSpecContent,
                              draftSpecContent,
                              latestBootstrapRun,
                              latestSynthesisRun,
                              bootstrapMetadata,
                              onRefreshKnowledge,
                              refreshPending,
                              bddAggregates
                          }: {
    specStage: SpecStageKey;
    onSelectSpecStage: (stage: SpecStageKey) => void;
    currentSpec?: SpecFile;
    currentSpecLoading: boolean;
    approvedSpecContent: string;
    draftSpecContent: string;
    latestBootstrapRun: RunSummary | null;
    latestSynthesisRun: RunSummary | null;
    bootstrapMetadata: BootstrapRunMetadata | null;
    onRefreshKnowledge: () => void;
    refreshPending: boolean;
    bddAggregates: BddAggregate[];
}) {
    const diffAvailable = Boolean(approvedSpecContent || draftSpecContent);
    const diffChanged = approvedSpecContent !== draftSpecContent;
    const currentExists = currentSpec?.exists ?? false;
    const currentContent = currentSpec?.content ?? "";
    const bootstrapTone = latestBootstrapRun ? toneForRunStatus(latestBootstrapRun.status) : "neutral";
    const bootstrapProgress = summarizeBootstrapProgress(latestBootstrapRun);
    const stages = bootstrapMetadata?.stages ?? [];
    const runningStage = stages.find((stage) => stage.status === "RUNNING") ?? null;
    const failedStage = stages.find((stage) => stage.status === "FAILED") ?? null;
    const synthesisMetadata = parseSynthesisRunMetadata(latestSynthesisRun?.metadataJson);
    const emptySpecTitle = latestBootstrapRun?.status === "FAILED"
        ? "Semantic derivation failed"
        : runningStage?.kind === "SYNTHESIS"
            ? "Semantic synthesis in progress"
            : runningStage?.kind === "APPROVE"
                ? "Approval in progress"
                : "Semantic spec not available";
    const emptySpecDetail = latestBootstrapRun?.status === "FAILED"
        ? failedStage?.detail || latestBootstrapRun.logText || "The latest semantic derivation failed before writing the spec artifact."
        : runningStage?.kind === "SYNTHESIS"
            ? "The draft and approved spec files stay empty until synthesis finishes and the derived document validates."
            : runningStage?.kind === "APPROVE"
                ? "The semantic draft exists conceptually, but the approved file is only written after validation and approval complete."
                : "Refresh knowledge to derive the selected semantic spec artifact.";
    const emptyDiffTitle = latestBootstrapRun?.status === "FAILED"
        ? "No semantic spec diff available"
        : runningStage
            ? "Semantic diff pending"
            : "No semantic spec diff available";
    const emptyDiffDetail = latestBootstrapRun?.status === "FAILED"
        ? failedStage?.detail || latestBootstrapRun.logText || "The latest bootstrap failed before draft and approved specs were produced."
        : runningStage
            ? "Draft and approved specs will appear here after synthesis, validation, and approval complete."
            : "Draft and approved semantic specs will appear here after a successful refresh.";
    const stageBadgeTone = currentExists ? "positive" : runningStage ? "info" : "neutral";
    const stageBadgeLabel = currentExists
        ? SPEC_STAGE_LABELS[specStage]
        : runningStage
            ? `${humanizeRunKind(runningStage.kind)} in progress`
            : "not derived";
    const aiTone = latestSynthesisRun?.status === "FAILED"
        ? "danger"
        : synthesisMetadata?.aiApplied
            ? "positive"
            : synthesisMetadata?.aiFallbackUsed
                ? "warning"
                : synthesisMetadata?.aiAttempted
                    ? "info"
                    : "neutral";
    const aiStatus = !latestSynthesisRun
        ? "unknown"
        : latestSynthesisRun.status === "RUNNING"
            ? "in progress"
            : latestSynthesisRun.status === "FAILED"
                ? "failed"
                : synthesisMetadata?.aiApplied
                    ? "applied"
                    : synthesisMetadata?.aiFallbackUsed
                        ? "fallback"
                        : synthesisMetadata?.aiAttempted
                            ? "attempted"
                            : "not attempted";
    const aiProviderModel = synthesisMetadata?.aiProvider
        ? synthesisMetadata.aiModel
            ? `${synthesisMetadata.aiProvider} / ${synthesisMetadata.aiModel}`
            : synthesisMetadata.aiProvider
        : "n/a";
    const aiReason = synthesisMetadata?.aiFallbackReason?.trim()
        || (latestSynthesisRun?.status === "FAILED" ? latestSynthesisRun.logText?.trim() : "")
        || "";
    const aiDetail = latestSynthesisRun?.status === "RUNNING"
        ? "AI refinement status will appear after synthesis completes."
        : latestSynthesisRun?.status === "FAILED"
            ? aiReason || "The synthesis run failed before a refined draft was produced."
            : synthesisMetadata?.aiApplied
                ? "AI-refined semantic output passed validation and was kept."
                : synthesisMetadata?.aiFallbackUsed
                    ? aiReason || "AI refinement did not survive validation, so the deterministic draft was kept."
                    : synthesisMetadata?.aiAttempted
                        ? "AI refinement was attempted but did not materially change the final approved draft."
                        : latestSynthesisRun
                            ? "No AI refinement was attempted. The deterministic draft became the final spec."
                            : "The latest synthesis run records whether AI was attempted and whether fallback was used.";

    return (
        <div className="stage-grid">
            <div className="stage-main">
                <Panel>
                    <SectionHeader
                        eyebrow="Semantic Spec"
                        title="Derived semantic spec"
                        description="Inspect the read-only semantic YAML derived from deterministic evidence and AI synthesis."
                        badge={
                            <StatusBadge tone={stageBadgeTone}>
                                {stageBadgeLabel}
                            </StatusBadge>
                        }
                        actions={
                            <div className="toolbar-row">
                                {(["current", "approved", "draft"] as const).map((stage) => (
                                    <button
                                        key={stage}
                                        type="button"
                                        className={`stage-chip ${specStage === stage ? "active" : ""}`}
                                        onClick={() => onSelectSpecStage(stage)}
                                    >
                                        {SPEC_STAGE_LABELS[stage]}
                                    </button>
                                ))}
                                <button
                                    type="button"
                                    className="primary-button"
                                    onClick={onRefreshKnowledge}
                                    disabled={refreshPending}
                                >
                                    {refreshPending ? "Refreshing..." : "Refresh knowledge"}
                                </button>
                            </div>
                        }
                    />

                    {currentSpecLoading ? (
                        <EmptyState
                            title="Loading semantic spec"
                            detail="Reading the currently selected semantic spec artifact."
                        />
                    ) : currentExists ? (
                        <div className="editor-shell">
                            <Editor
                                height="520px"
                                defaultLanguage="yaml"
                                language="yaml"
                                theme="vs-dark"
                                value={currentContent}
                                options={{
                                    readOnly: true,
                                    minimap: {enabled: false},
                                    fontSize: 13,
                                    wordWrap: "on",
                                    automaticLayout: true
                                }}
                            />
                        </div>
                    ) : (
                        <EmptyState
                            title={emptySpecTitle}
                            detail={emptySpecDetail}
                        />
                    )}
                </Panel>

                <Panel>
                    <SectionHeader
                        eyebrow="Diff"
                        title="Approved vs draft"
                        description="Compare the approved semantic baseline against the latest draft produced by the refresh pipeline."
                        badge={
                            <StatusBadge tone={!diffAvailable ? "neutral" : diffChanged ? "warning" : "positive"}>
                                {!diffAvailable ? "empty" : diffChanged ? "changed" : "in sync"}
                            </StatusBadge>
                        }
                    />

                    {diffAvailable ? (
                        <div className="diff-shell">
                            <DiffEditor
                                height="360px"
                                theme="vs-dark"
                                original={approvedSpecContent}
                                modified={draftSpecContent}
                                language="yaml"
                                options={{
                                    renderSideBySide: true,
                                    minimap: {enabled: false},
                                    fontSize: 13,
                                    wordWrap: "on",
                                    automaticLayout: true,
                                    readOnly: true
                                }}
                            />
                        </div>
                    ) : (
                        <EmptyState
                            title={emptyDiffTitle}
                            detail={emptyDiffDetail}
                        />
                    )}
                </Panel>
            </div>

            <div className="stage-side">
                <BddScenariosViewer aggregates={bddAggregates}/>

                <Panel>
                    <SectionHeader
                        eyebrow="Lifecycle"
                        title="Derived artifact posture"
                        badge={
                            <StatusBadge tone={bootstrapTone}>
                                {latestBootstrapRun ? summarizeBootstrapRun(latestBootstrapRun) : "idle"}
                            </StatusBadge>
                        }
                    />

                    <KeyValueList
                        items={[
                            {label: "Current stage", value: SPEC_STAGE_LABELS[specStage]},
                            {label: "Current available", value: currentExists ? "yes" : "no"},
                            {label: "Approved available", value: approvedSpecContent ? "yes" : "no"},
                            {label: "Draft available", value: draftSpecContent ? "yes" : "no"},
                            {
                                label: "Latest bootstrap",
                                value: latestBootstrapRun
                                    ? `${latestBootstrapRun.status.toLowerCase()} at ${formatDate(latestBootstrapRun.startedAt)}`
                                    : "not run"
                            }
                        ]}
                    />

                    <div className="panel-stack progress-panel-copy">
                        <ProgressMeter
                            value={bootstrapProgress.progressValue}
                            max={bootstrapProgress.progressMax}
                            tone={bootstrapProgress.tone}
                            animated={bootstrapProgress.animated}
                            label={bootstrapProgress.headline}
                        />
                        <p className="inline-empty">{bootstrapProgress.detail}</p>
                    </div>

                    <div className="info-list" style={{marginTop: "1rem"}}>
                        <InfoListRow
                            title="AI refinement"
                            subtitle={aiDetail}
                            tone={aiTone}
                            trailing={<StatusBadge tone={aiTone}>{aiStatus}</StatusBadge>}
                        />
                    </div>

                    <KeyValueList
                        className="key-value-list compact"
                        items={[
                            {label: "Latest synthesis", value: latestSynthesisRun ? latestSynthesisRun.status.toLowerCase() : "not run"},
                            {label: "AI provider", value: aiProviderModel},
                            {label: "AI attempted", value: synthesisMetadata?.aiAttempted ? "yes" : "no"},
                            {label: "AI applied", value: synthesisMetadata?.aiApplied ? "yes" : "no"},
                            {label: "Fallback used", value: synthesisMetadata?.aiFallbackUsed ? "yes" : "no"}
                        ]}
                    />

                    {stages.length ? (
                        <div className="info-list" style={{marginTop: "1rem"}}>
                            {stages.map((stage) => (
                                <InfoListRow
                                    key={stage.kind}
                                    title={humanizeRunKind(stage.kind)}
                                    subtitle={stage.detail || stage.status.toLowerCase()}
                                    tone={
                                        stage.status === "FAILED"
                                            ? "danger"
                                            : stage.status === "RUNNING"
                                                ? "info"
                                                : stage.status === "SUCCEEDED"
                                                    ? "positive"
                                                    : "neutral"
                                    }
                                    trailing={
                                        <StatusBadge
                                            tone={
                                                stage.status === "FAILED"
                                                    ? "danger"
                                                    : stage.status === "RUNNING"
                                                        ? "info"
                                                        : stage.status === "SUCCEEDED"
                                                            ? "positive"
                                                            : "neutral"
                                            }
                                        >
                                            {stage.status.toLowerCase()}
                                        </StatusBadge>
                                    }
                                />
                            ))}
                        </div>
                    ) : null}
                </Panel>

                <Panel>
                    <SectionHeader eyebrow="Policy" title="Read-only derived model"/>
                    <div className="panel-stack">
                        <p className="inline-empty">
                            Semantic specs are derived artifacts. They are refreshed from evidence, optionally refined by AI,
                            validated, and then promoted to approved automatically.
                        </p>
                        <p className="inline-empty">
                            Manual editing, proposals, and authoring flows are intentionally disabled in this workbench mode.
                        </p>
                    </div>
                </Panel>
            </div>
        </div>
    );
}
