import { WorkbenchSidebar } from "./components/WorkbenchSidebar";
import { WorkbenchHeader } from "./components/WorkbenchHeader";
import { StageNavigator } from "./components/StageNavigator";
import { ContractsStage } from "./components/ContractsStage";
import { GenerationStage } from "./components/GenerationStage";
import { AskStage } from "./components/AskStage";
import { GraphStage } from "./components/GraphStage";
import { IrStage } from "./components/IrStage";
import { SpecStage } from "./components/SpecStage";
import { EmptyState, Panel, SectionHeader } from "./components/primitives";
import { useWorkbenchController } from "./useWorkbenchController";
import { parseBddAggregates } from "./utils";

export function WorkbenchApp() {
  const controller = useWorkbenchController();
  const bddAggregates = parseBddAggregates(controller.editorValue);

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
            currentBlocker={controller.currentBlocker}
            nextAction={controller.nextAction}
            onNextAction={controller.handleNextAction}
          />
        ) : (
          <Panel className="briefing-panel">
            <SectionHeader eyebrow="Workbench" title="Start with a workspace import" />
            <EmptyState
              title="No workspace selected"
              detail="Import a local service from the left rail to open the stage-oriented workbench for specs, IR, generation, contracts, and graph review."
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
              description="The workbench reflects the compiler pipeline from working spec through IR, generation, contract review, and lineage graph inspection."
            />
            <div className="empty-grid">
              <EmptyState
                title="Spec"
                detail="Review or draft YAML, validate it, and compare proposed changes before apply."
              />
              <EmptyState
                title="IR"
                detail="Inspect canonical IR output and the evidence that feeds extraction-backed reasoning."
              />
              <EmptyState
                title="Generation"
                detail="Build draft specs and run deterministic generation against the managed workspace."
              />
            </div>
          </Panel>
        ) : controller.activeStage === "spec" ? (
          <SpecStage
            specStage={controller.specStage}
            onSelectSpecStage={controller.setSpecStage}
            editorValue={controller.editorValue}
            onEditorChange={controller.updateEditor}
            editorDirty={controller.editorDirty}
            onSave={() => controller.saveSpecMutation.mutate()}
            savePending={controller.saveSpecMutation.isPending}
            onValidate={() => controller.validateSpecMutation.mutate()}
            validatePending={controller.validateSpecMutation.isPending}
            validationReport={controller.validationReport}
            proposalInstruction={controller.proposalInstruction}
            onProposalInstructionChange={controller.setProposalInstruction}
            onCreateSpecProposal={() => controller.specProposalMutation.mutate()}
            specProposalPending={controller.specProposalMutation.isPending}
            storyForm={controller.storyForm}
            onStoryFieldChange={controller.updateStoryField}
            onCreateStoryProposal={() => controller.storyProposalMutation.mutate()}
            storyProposalPending={controller.storyProposalMutation.isPending}
            proposals={controller.allProposals}
            selectedProposalId={controller.selectedProposalId}
            onSelectProposal={controller.setSelectedProposalId}
            selectedProposal={controller.selectedProposal}
            approvedSpecContent={controller.approvedSpecContent}
            proposalPatch={controller.proposalPatch}
            onApplyProposal={(proposalId) => controller.applyProposalMutation.mutate(proposalId)}
            applyProposalPending={controller.applyProposalMutation.isPending}
            bddAggregates={bddAggregates}
          />
        ) : controller.activeStage === "proposals" ? (
          <SpecStage
            specStage="current"
            onSelectSpecStage={() => {}}
            editorValue=""
            onEditorChange={() => {}}
            editorDirty={false}
            onSave={() => {}}
            savePending={false}
            onValidate={() => {}}
            validatePending={false}
            validationReport={null}
            proposalInstruction={controller.proposalInstruction}
            onProposalInstructionChange={controller.setProposalInstruction}
            onCreateSpecProposal={() => controller.specProposalMutation.mutate()}
            specProposalPending={controller.specProposalMutation.isPending}
            storyForm={controller.storyForm}
            onStoryFieldChange={controller.updateStoryField}
            onCreateStoryProposal={() => controller.storyProposalMutation.mutate()}
            storyProposalPending={controller.storyProposalMutation.isPending}
            proposals={controller.allProposals}
            selectedProposalId={controller.selectedProposalId}
            onSelectProposal={controller.setSelectedProposalId}
            selectedProposal={controller.selectedProposal}
            approvedSpecContent={controller.approvedSpecContent}
            proposalPatch={controller.proposalPatch}
            onApplyProposal={(proposalId) => controller.applyProposalMutation.mutate(proposalId)}
            applyProposalPending={controller.applyProposalMutation.isPending}
            bddAggregates={bddAggregates}
          />
        ) : controller.activeStage === "drift" ? (
          <ContractsStage
            drift={controller.driftQuery.data}
            driftLoading={controller.driftQuery.isLoading}
            contractDiff={controller.contractDiffQuery.data}
            contractLoading={controller.contractDiffQuery.isLoading}
            onScanDrift={() => controller.driftMutation.mutate()}
            driftPending={controller.driftMutation.isPending}
          />
        ) : controller.activeStage === "ask" ? (
          <AskStage
            projectId={controller.selectedProjectId}
            hasSpec={controller.hasAnySpec}
          />
        ) : (
          <GraphStage
            hasExtractionRun={controller.hasExtractionRun}
            graph={controller.graphQuery.data}
            graphLoading={controller.graphQuery.isLoading}
            graphSummary={controller.graphSummary}
            extraction={controller.extractionQuery.data}
            onRefreshExtraction={() => controller.extractMutation.mutate()}
            extractionPending={controller.extractMutation.isPending}
          />
        )}
      </main>
    </div>
  );
}
