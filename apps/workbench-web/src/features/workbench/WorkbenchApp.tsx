import {WorkbenchSidebar} from "./components/WorkbenchSidebar";
import {WorkbenchHeader} from "./components/WorkbenchHeader";
import {StageNavigator} from "./components/StageNavigator";
import {AskStage} from "./components/AskStage";
import {GraphStage} from "./components/GraphStage";
import {IrStage} from "./components/IrStage";
import {SpecStage} from "./components/SpecStage";
import {EmptyState, Panel, SectionHeader} from "./components/primitives";
import {useWorkbenchController} from "./useWorkbenchController";
import {parseBddAggregates} from "./utils";

export function WorkbenchApp() {
    const controller = useWorkbenchController();
    const bddAggregates = parseBddAggregates(controller.currentSpecContent);

    return (
        <div className="app-shell">
            <WorkbenchSidebar
                projects={controller.projectsQuery.data ?? []}
                selectedProjectId={controller.selectedProjectId}
                onSelectProject={controller.selectProject}
                importForm={controller.importForm}
                onImportFieldChange={controller.updateImportField}
                onImportWorkspace={() => controller.importProjectMutation.mutate()}
                importPending={controller.importProjectMutation.isPending}
                settings={controller.settingsQuery.data}
                settingsLoading={controller.settingsQuery.isLoading}
                flashMessage={controller.flashMessage}
            />

            <main className="workbench-main">
                {controller.selectedProject ? (
                    <WorkbenchHeader
                        project={controller.selectedProject}
                        latestRun={controller.latestRun}
                        latestBootstrapRun={controller.latestBootstrapRun}
                        currentBlocker={controller.currentBlocker}
                        nextAction={controller.nextAction}
                        onNextAction={controller.handleNextAction}
                    />
                ) : (
                    <Panel className="briefing-panel">
                        <SectionHeader eyebrow="Workbench" title="Start with a workspace import"/>
                        <EmptyState
                            title="No workspace selected"
                            detail="Import a local service from the left rail to start the async knowledge bootstrap and open the evidence, semantic spec, graph, and ask views."
                        />
                    </Panel>
                )}

                <StageNavigator
                    stages={controller.stageSummaries}
                    activeStage={controller.activeStage}
                    onSelectStage={controller.setActiveStage}
                />

                {!controller.selectedProject ? (
                    <Panel>
                        <SectionHeader
                            eyebrow="Pipeline"
                            title="Operator flow"
                            description="The workbench reflects the evidence-to-semantic pipeline from deterministic extraction through graph lineage and retrieval."
                        />
                        <div className="empty-grid">
                            <EmptyState
                                title="Evidence"
                                detail="Build the deterministic codebase snapshot, inspect adapter coverage, and verify cited evidence."
                            />
                            <EmptyState
                                title="Semantic Spec"
                                detail="Inspect the derived semantic spec that was synthesized from cited evidence."
                            />
                            <EmptyState
                                title="Graph"
                                detail="Inspect the semantic and evidence lineage graph once the approved baseline is ready."
                            />
                        </div>
                    </Panel>
                ) : controller.activeStage === "evidence" ? (
                    <IrStage
                        hasExtractionRun={controller.hasExtractionRun}
                        extraction={controller.extractionQuery.data}
                        extractionLoading={controller.extractionQuery.isLoading}
                        irPreviewJson={controller.irPreviewJson}
                        irLoading={controller.semanticSpecQuery.isLoading}
                        onRefreshKnowledge={() => controller.refreshKnowledgeMutation.mutate()}
                        refreshPending={controller.refreshKnowledgeMutation.isPending}
                    />
                ) : controller.activeStage === "spec" ? (
                    <SpecStage
                        specStage={controller.specStage}
                        onSelectSpecStage={controller.setSpecStage}
                        currentSpec={controller.currentSpecQuery.data}
                        currentSpecLoading={controller.currentSpecQuery.isLoading}
                        approvedSpecContent={controller.approvedSpecContent}
                        draftSpecContent={controller.draftSpecContent}
                        latestBootstrapRun={controller.latestBootstrapRun}
                        latestSynthesisRun={controller.latestSynthesisRun}
                        bootstrapMetadata={controller.bootstrapMetadata}
                        onRefreshKnowledge={() => controller.refreshKnowledgeMutation.mutate()}
                        refreshPending={controller.refreshKnowledgeMutation.isPending}
                        bddAggregates={bddAggregates}
                    />
                ) : controller.activeStage === "ask" ? (
                    <AskStage
                        projectId={controller.selectedProjectId}
                        hasSpec={controller.hasApprovedSpec}
                    />
                ) : (
                    <GraphStage
                        hasExtractionRun={controller.hasExtractionRun}
                        graph={controller.graphQuery.data}
                        graphLoading={controller.graphQuery.isLoading}
                        graphSummary={controller.graphSummary}
                        extraction={controller.extractionQuery.data}
                        latestBootstrapRun={controller.latestBootstrapRun}
                        bootstrapMetadata={controller.bootstrapMetadata}
                        onRefreshKnowledge={() => controller.refreshKnowledgeMutation.mutate()}
                        refreshPending={controller.refreshKnowledgeMutation.isPending}
                    />
                )}
            </main>
        </div>
    );
}
