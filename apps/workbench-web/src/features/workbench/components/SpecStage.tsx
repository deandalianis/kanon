import { DiffEditor, Editor } from "@monaco-editor/react";
import type { ValidationReport } from "../../../types";
import { SPEC_STAGE_LABELS, type ProposalView, type SpecStageKey, type StoryFormState } from "../types";
import { EmptyState, Panel, SectionHeader, StatusBadge } from "./primitives";

export function SpecStage({
  specStage,
  onSelectSpecStage,
  editorValue,
  onEditorChange,
  editorDirty,
  onSave,
  savePending,
  onValidate,
  validatePending,
  validationReport,
  proposalInstruction,
  onProposalInstructionChange,
  onCreateSpecProposal,
  specProposalPending,
  storyForm,
  onStoryFieldChange,
  onCreateStoryProposal,
  storyProposalPending,
  proposals,
  selectedProposalId,
  onSelectProposal,
  selectedProposal,
  approvedSpecContent,
  proposalPatch,
  onApplyProposal,
  applyProposalPending
}: {
  specStage: SpecStageKey;
  onSelectSpecStage: (stage: SpecStageKey) => void;
  editorValue: string;
  onEditorChange: (value: string) => void;
  editorDirty: boolean;
  onSave: () => void;
  savePending: boolean;
  onValidate: () => void;
  validatePending: boolean;
  validationReport: ValidationReport | null;
  proposalInstruction: string;
  onProposalInstructionChange: (value: string) => void;
  onCreateSpecProposal: () => void;
  specProposalPending: boolean;
  storyForm: StoryFormState;
  onStoryFieldChange: (field: keyof StoryFormState, value: string) => void;
  onCreateStoryProposal: () => void;
  storyProposalPending: boolean;
  proposals: ProposalView[];
  selectedProposalId: string;
  onSelectProposal: (proposalId: string) => void;
  selectedProposal: ProposalView | null;
  approvedSpecContent: string;
  proposalPatch: string;
  onApplyProposal: (proposalId: string) => void;
  applyProposalPending: boolean;
}) {
  const diffTitle = selectedProposal ? "Approved spec vs selected proposal" : "Approved spec vs draft";
  const diffBadgeTone = selectedProposal?.kind === "STORY" ? "info" : "warning";

  return (
    <div className="stage-grid">
      <div className="stage-main">
        <Panel>
          <SectionHeader
            eyebrow="Spec"
            title="Working spec editor"
            description="Edit the active YAML stage, validate it, and keep the working state explicit."
            badge={
              <StatusBadge tone={editorDirty ? "warning" : "positive"}>
                {editorDirty ? "unsaved" : SPEC_STAGE_LABELS[specStage]}
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
                  className="secondary-button"
                  onClick={onValidate}
                  disabled={validatePending}
                >
                  {validatePending ? "Validating..." : "Validate"}
                </button>
                <button
                  type="button"
                  className="primary-button"
                  onClick={onSave}
                  disabled={savePending || !editorDirty}
                >
                  {savePending ? "Saving..." : "Save"}
                </button>
              </div>
            }
          />

          <div className="editor-shell">
            <Editor
              height="520px"
              defaultLanguage="yaml"
              language="yaml"
              theme="vs-dark"
              value={editorValue}
              onChange={(value) => onEditorChange(value ?? "")}
              options={{
                minimap: { enabled: false },
                fontSize: 13,
                wordWrap: "on",
                automaticLayout: true
              }}
            />
          </div>
        </Panel>

        <Panel>
          <SectionHeader
            eyebrow="Diff review"
            title={diffTitle}
            description="Compare the approved baseline against the current draft or a selected proposal before apply."
            badge={
              <StatusBadge tone={selectedProposal ? diffBadgeTone : "neutral"}>
                {selectedProposal ? selectedProposal.kind : "Draft"}
              </StatusBadge>
            }
            actions={
              selectedProposal && selectedProposal.status !== "APPLIED" ? (
                <button
                  type="button"
                  className="primary-button"
                  onClick={() => onApplyProposal(selectedProposal.id)}
                  disabled={applyProposalPending}
                >
                  {applyProposalPending ? "Applying..." : "Apply selected proposal"}
                </button>
              ) : undefined
            }
          />

          <div className="diff-shell">
            <DiffEditor
              height="360px"
              theme="vs-dark"
              original={approvedSpecContent}
              modified={proposalPatch}
              language="yaml"
              options={{
                renderSideBySide: true,
                minimap: { enabled: false },
                fontSize: 13,
                wordWrap: "on",
                automaticLayout: true
              }}
            />
          </div>

          {selectedProposal ? (
            <div className="panel-stack">
              <div className="subtle-block">
                <div className="subtle-block-head">
                  <strong>{selectedProposal.title}</strong>
                  <StatusBadge tone={selectedProposal.status === "APPLIED" ? "positive" : "info"}>
                    {selectedProposal.status.toLowerCase()}
                  </StatusBadge>
                </div>
                <p>{selectedProposal.summary}</p>
              </div>

              <div className="checklist-list">
                {selectedProposal.checklist.map((item, index) => (
                  <div key={`${selectedProposal.id}-${index}`} className="checklist-row">
                    <span>{item}</span>
                  </div>
                ))}
                {!selectedProposal.checklist.length && (
                  <p className="inline-empty">No checklist items were included with this proposal.</p>
                )}
              </div>
            </div>
          ) : (
            <EmptyState
              title="No proposal selected"
              detail="The diff currently compares the approved baseline against the latest draft spec."
            />
          )}
        </Panel>
      </div>

      <div className="stage-side">
        <Panel>
          <SectionHeader
            eyebrow="Validation"
            title="Spec diagnostics"
            badge={
              <StatusBadge tone={!validationReport ? "neutral" : validationReport.valid ? "positive" : "danger"}>
                {!validationReport ? "not run" : validationReport.valid ? "valid" : "issues"}
              </StatusBadge>
            }
          />

          {validationReport?.issues.length ? (
            <div className="issue-list">
              {validationReport.issues.map((issue, index) => (
                <div key={`${issue.code}-${index}`} className={`issue-row ${issue.level === "ERROR" ? "error" : "warn"}`}>
                  <div className="issue-head">
                    <strong>{issue.code}</strong>
                    <span>{issue.path}</span>
                  </div>
                  <p>{issue.message}</p>
                </div>
              ))}
            </div>
          ) : (
            <EmptyState
              title={validationReport?.valid ? "Spec is valid" : "Validation not run"}
              detail={
                validationReport?.valid
                  ? "The last validation pass reported no blocking issues for the active editor content."
                  : "Run validation after editing to capture structural or semantic issues."
              }
            />
          )}
        </Panel>

        <Panel>
          <SectionHeader
            eyebrow="Proposals"
            title="Proposal queue"
            badge={<StatusBadge tone={proposals.length ? "info" : "neutral"}>{proposals.length}</StatusBadge>}
          />

          <div className="form-section">
            <h3>Create spec proposal</h3>
            <label className="form-field">
              <span>Instruction</span>
              <textarea
                className="text-area"
                rows={4}
                value={proposalInstruction}
                onChange={(event) => onProposalInstructionChange(event.target.value)}
                placeholder="Add approval workflow, pagination limits, or a new aggregate."
              />
            </label>
            <button
              type="button"
              className="primary-button"
              onClick={onCreateSpecProposal}
              disabled={specProposalPending || !proposalInstruction.trim()}
            >
              {specProposalPending ? "Drafting..." : "Create spec proposal"}
            </button>
          </div>

          <div className="form-section">
            <h3>Plan story</h3>
            <label className="form-field">
              <span>Story title</span>
              <input
                className="text-input"
                value={storyForm.title}
                onChange={(event) => onStoryFieldChange("title", event.target.value)}
              />
            </label>
            <label className="form-field">
              <span>User story</span>
              <textarea
                className="text-area"
                rows={3}
                value={storyForm.story}
                onChange={(event) => onStoryFieldChange("story", event.target.value)}
              />
            </label>
            <label className="form-field">
              <span>Acceptance criteria</span>
              <textarea
                className="text-area"
                rows={4}
                value={storyForm.acceptanceCriteria}
                onChange={(event) => onStoryFieldChange("acceptanceCriteria", event.target.value)}
              />
            </label>
            <button
              type="button"
              className="secondary-button"
              onClick={onCreateStoryProposal}
              disabled={
                storyProposalPending ||
                !storyForm.title.trim() ||
                !storyForm.story.trim() ||
                !storyForm.acceptanceCriteria.trim()
              }
            >
              {storyProposalPending ? "Planning..." : "Create story proposal"}
            </button>
          </div>

          <div className="proposal-list">
            {proposals.map((proposal) => (
              <button
                key={proposal.id}
                type="button"
                className={`proposal-item ${selectedProposalId === proposal.id ? "active" : ""}`}
                onClick={() => onSelectProposal(proposal.id)}
              >
                <div className="proposal-item-head">
                  <strong>{proposal.title}</strong>
                  <StatusBadge tone={proposal.kind === "STORY" ? "info" : "warning"}>
                    {proposal.kind}
                  </StatusBadge>
                </div>
                <p>{proposal.summary}</p>
                <div className="proposal-item-meta">
                  <span>{`${proposal.auditProvider} / ${proposal.auditModel}`}</span>
                  <StatusBadge tone={proposal.status === "APPLIED" ? "positive" : "neutral"}>
                    {proposal.status.toLowerCase()}
                  </StatusBadge>
                </div>
              </button>
            ))}
            {!proposals.length && (
              <EmptyState
                title="Proposal queue empty"
                detail="Create a spec patch or a story-backed proposal to open a structured review flow."
              />
            )}
          </div>
        </Panel>
      </div>
    </div>
  );
}
