import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { startTransition, useDeferredValue, useEffect, useState } from "react";
import { api, asJson, asPut } from "../../api";
import type {
  CapabilitySet,
  ContractDiff,
  DriftReport,
  ExtractionResult,
  GraphView,
  RunSummary,
  RuntimeSettings,
  SpecFile,
  SpecProposal,
  StorySpecProposal,
  ValidationReport,
  WorkspaceRef
} from "../../types";
import { invalidateProjectSlices, workbenchKeys } from "./queryKeys";
import {
  IMPORT_DEFAULTS,
  PROJECT_STORAGE_KEY,
  STORY_DEFAULTS,
  type ImportFormState,
  type PipelineStage,
  type SpecStageKey,
  type StoryFormState
} from "./types";
import {
  buildFlowEdges,
  buildFlowNodes,
  buildStageSummaries,
  countContractDelta,
  describeCurrentBlocker,
  deriveNextAction,
  errorMessage,
  hasSuccessfulRun,
  mergeProposals,
  summarizeGraph
} from "./utils";

type ImportTextField = "name" | "sourcePath" | "serviceName" | "basePackage";
type StoryTextField = keyof StoryFormState;

function persistProjectSelection(projectId: string) {
  localStorage.setItem(PROJECT_STORAGE_KEY, projectId);
}

function clearProjectSelection() {
  localStorage.removeItem(PROJECT_STORAGE_KEY);
}

export function useWorkbenchController() {
  const queryClient = useQueryClient();
  const [selectedProjectId, setSelectedProjectId] = useState<string>(() => localStorage.getItem(PROJECT_STORAGE_KEY) ?? "");
  const [activeStage, setActiveStage] = useState<PipelineStage>("spec");
  const [specStage, setSpecStage] = useState<SpecStageKey>("current");
  const [editorValue, setEditorValue] = useState("");
  const [editorDirty, setEditorDirty] = useState(false);
  const [validationReport, setValidationReport] = useState<ValidationReport | null>(null);
  const [proposalInstruction, setProposalInstruction] = useState("");
  const [selectedProposalId, setSelectedProposalId] = useState("");
  const [importForm, setImportForm] = useState<ImportFormState>(IMPORT_DEFAULTS);
  const [storyForm, setStoryForm] = useState<StoryFormState>(STORY_DEFAULTS);
  const [flashMessage, setFlashMessage] = useState("");

  const settingsQuery = useQuery({
    queryKey: workbenchKeys.settings,
    queryFn: () => api<RuntimeSettings>("/api/settings")
  });

  const projectsQuery = useQuery({
    queryKey: workbenchKeys.projects,
    queryFn: () => api<WorkspaceRef[]>("/api/projects")
  });

  const selectedProject = projectsQuery.data?.find((project) => project.id === selectedProjectId) ?? null;
  const hasProject = Boolean(selectedProject);

  const runsQuery = useQuery({
    queryKey: workbenchKeys.runs(selectedProjectId),
    queryFn: () => api<RunSummary[]>(`/api/projects/${selectedProjectId}/runs`),
    enabled: hasProject
  });

  const currentSpecQuery = useQuery({
    queryKey: workbenchKeys.spec(selectedProjectId, specStage),
    queryFn: () => api<SpecFile>(`/api/projects/${selectedProjectId}/spec?stage=${specStage}`),
    enabled: hasProject
  });

  const approvedSpecQuery = useQuery({
    queryKey: workbenchKeys.spec(selectedProjectId, "approved"),
    queryFn: () => api<SpecFile>(`/api/projects/${selectedProjectId}/spec?stage=approved`),
    enabled: hasProject
  });

  const draftSpecQuery = useQuery({
    queryKey: workbenchKeys.spec(selectedProjectId, "draft"),
    queryFn: () => api<SpecFile>(`/api/projects/${selectedProjectId}/spec?stage=draft`),
    enabled: hasProject
  });

  const specProposalsQuery = useQuery({
    queryKey: workbenchKeys.specProposals(selectedProjectId),
    queryFn: () => api<SpecProposal[]>(`/api/projects/${selectedProjectId}/proposals/spec`),
    enabled: hasProject
  });

  const storyProposalsQuery = useQuery({
    queryKey: workbenchKeys.storyProposals(selectedProjectId),
    queryFn: () => api<StorySpecProposal[]>(`/api/projects/${selectedProjectId}/proposals/story`),
    enabled: hasProject
  });

  const hasAnySpec = Boolean(
    currentSpecQuery.data?.exists || approvedSpecQuery.data?.exists || draftSpecQuery.data?.exists
  );
  const hasExtractionRun = hasSuccessfulRun(runsQuery.data, "EXTRACTION");
  const hasDriftRun = hasSuccessfulRun(runsQuery.data, "DRIFT_SCAN");
  const hasGenerationRun = hasSuccessfulRun(runsQuery.data, "GENERATION");

  const extractionQuery = useQuery({
    queryKey: workbenchKeys.extraction(selectedProjectId),
    queryFn: () => api<ExtractionResult>(`/api/projects/${selectedProjectId}/artifacts/extraction`),
    enabled: hasProject && hasExtractionRun
  });

  const irQuery = useQuery({
    queryKey: workbenchKeys.ir(selectedProjectId),
    queryFn: () => api<unknown>(`/api/projects/${selectedProjectId}/ir`),
    enabled: hasProject && hasAnySpec && (activeStage === "ir" || activeStage === "graph")
  });

  const contractDiffQuery = useQuery({
    queryKey: workbenchKeys.contracts(selectedProjectId),
    queryFn: () => api<ContractDiff>(`/api/projects/${selectedProjectId}/contracts/diff`),
    enabled: hasProject && hasAnySpec && (activeStage === "contracts" || hasGenerationRun)
  });

  const driftQuery = useQuery({
    queryKey: workbenchKeys.drift(selectedProjectId),
    queryFn: () => api<DriftReport>(`/api/projects/${selectedProjectId}/artifacts/drift`),
    enabled: hasProject && hasDriftRun
  });

  const graphQuery = useQuery({
    queryKey: workbenchKeys.graph(selectedProjectId),
    queryFn: () => api<GraphView>(`/api/projects/${selectedProjectId}/graph/lineage`),
    enabled: hasProject && hasAnySpec && hasExtractionRun && activeStage === "graph"
  });

  useEffect(() => {
    if (!projectsQuery.data) {
      return;
    }

    if (!projectsQuery.data.length) {
      if (selectedProjectId) {
        setSelectedProjectId("");
        clearProjectSelection();
      }
      return;
    }

    if (selectedProjectId && projectsQuery.data.some((project) => project.id === selectedProjectId)) {
      return;
    }

    const nextId = projectsQuery.data[0].id;
    setSelectedProjectId(nextId);
    persistProjectSelection(nextId);
  }, [projectsQuery.data, selectedProjectId]);

  useEffect(() => {
    if (!selectedProjectId) {
      setEditorValue("");
      setEditorDirty(false);
      setValidationReport(null);
    }
  }, [selectedProjectId]);

  useEffect(() => {
    if (!selectedProjectId || !currentSpecQuery.data) {
      return;
    }

    setEditorValue(currentSpecQuery.data.content ?? "");
    setEditorDirty(false);
    setValidationReport(null);
  }, [currentSpecQuery.data, selectedProjectId]);

  const allProposals = mergeProposals(
    specProposalsQuery.data ?? [],
    storyProposalsQuery.data ?? []
  );
  const reviewableProposals = allProposals.filter(
    (proposal) => proposal.status !== "APPLIED" && proposal.status !== "REJECTED"
  );

  useEffect(() => {
    if (!allProposals.length) {
      setSelectedProposalId("");
      return;
    }

    if (selectedProposalId && allProposals.some((proposal) => proposal.id === selectedProposalId)) {
      return;
    }

    setSelectedProposalId((reviewableProposals[0] ?? allProposals[0]).id);
  }, [allProposals, reviewableProposals, selectedProposalId]);

  const selectedProposal = allProposals.find((proposal) => proposal.id === selectedProposalId) ?? null;
  const deferredIr = useDeferredValue(irQuery.data);
  const latestRun = runsQuery.data?.[0] ?? null;
  const approvedSpecContent = approvedSpecQuery.data?.content ?? "";
  const draftSpecContent = draftSpecQuery.data?.content ?? "";
  const proposalPatch = selectedProposal?.specPatch || draftSpecContent;
  const graphNodes = buildFlowNodes(graphQuery.data);
  const graphEdges = buildFlowEdges(graphQuery.data);
  const graphSummary = summarizeGraph(graphQuery.data);
  const topFacts = (extractionQuery.data?.facts ?? []).slice(0, 8);
  const topEvidence = (extractionQuery.data?.provenance ?? []).slice(0, 8);
  const topConflicts = (extractionQuery.data?.conflicts ?? []).slice(0, 6);
  const topDriftItems = (driftQuery.data?.items ?? []).slice(0, 8);
  const blockingDrift = (driftQuery.data?.items ?? []).filter((item) => item.blocking);
  const contractDeltaCount = countContractDelta(contractDiffQuery.data);
  const currentBlocker = describeCurrentBlocker({
    hasExtractionRun,
    hasAnySpec,
    editorDirty,
    validationReport,
    extraction: extractionQuery.data,
    drift: driftQuery.data,
    contractDeltaCount
  });
  const nextAction = deriveNextAction({
    hasExtractionRun,
    hasAnySpec,
    editorDirty,
    validationReport,
    extraction: extractionQuery.data,
    drift: driftQuery.data,
    contractDeltaCount,
    proposalsCount: reviewableProposals.length
  });
  const stageSummaries = buildStageSummaries({
    hasProject,
    hasAnySpec,
    hasExtractionRun,
    hasDriftRun,
    editorDirty,
    validationReport,
    extraction: extractionQuery.data,
    drift: driftQuery.data,
    contractDeltaCount,
    graphLoaded: Boolean(graphQuery.data),
    graphSummary,
    proposalsCount: reviewableProposals.length,
    latestRun
  });
  const irPreviewJson = validationReport?.canonicalIrJson
    ? validationReport.canonicalIrJson
    : deferredIr
      ? JSON.stringify(deferredIr, null, 2)
      : "";

  function selectProject(projectId: string) {
    startTransition(() => {
      setSelectedProjectId(projectId);
      persistProjectSelection(projectId);
      setActiveStage("spec");
      setSpecStage("current");
    });
  }

  function showError(error: unknown) {
    setFlashMessage(errorMessage(error));
  }

  function updateEditor(nextValue: string) {
    setEditorValue(nextValue);
    setEditorDirty(true);
  }

  function updateImportField(field: ImportTextField, value: string) {
    setImportForm((current) => ({
      ...current,
      [field]: value
    }));
  }

  function updateStoryField(field: StoryTextField, value: string) {
    setStoryForm((current) => ({
      ...current,
      [field]: value
    }));
  }

  function updateCapability(key: keyof CapabilitySet) {
    setImportForm((current) => ({
      ...current,
      capabilities: {
        ...current.capabilities,
        [key]: !current.capabilities[key]
      }
    }));
  }

  const importProjectMutation = useMutation({
    mutationFn: () => api<WorkspaceRef>("/api/projects/import", asJson(importForm)),
    onSuccess: (workspace) => {
      startTransition(() => {
        setSelectedProjectId(workspace.id);
        persistProjectSelection(workspace.id);
        setActiveStage("spec");
      });
      setImportForm(IMPORT_DEFAULTS);
      setFlashMessage(`Imported ${workspace.name}`);
      void queryClient.invalidateQueries({ queryKey: workbenchKeys.projects });
    },
    onError: showError
  });

  const extractMutation = useMutation({
    mutationFn: () => api(`/api/projects/${selectedProjectId}/extract`, { method: "POST" }),
    onSuccess: () => {
      setActiveStage("ir");
      invalidateProjectSlices(queryClient, selectedProjectId);
      setFlashMessage("Extraction refreshed");
    },
    onError: showError
  });

  const buildDraftMutation = useMutation({
    mutationFn: () => api(`/api/projects/${selectedProjectId}/draft-spec`, { method: "POST" }),
    onSuccess: () => {
      setSpecStage("draft");
      setActiveStage("generation");
      invalidateProjectSlices(queryClient, selectedProjectId);
      setFlashMessage("Draft spec rebuilt from extraction");
    },
    onError: showError
  });

  const validateSpecMutation = useMutation({
    mutationFn: () =>
      api<ValidationReport>(
        `/api/projects/${selectedProjectId}/spec/validate`,
        asJson({ stage: specStage, content: editorValue })
      ),
    onSuccess: (report) => {
      setValidationReport(report);
      setFlashMessage(report.valid ? "Spec is valid" : "Spec validation found issues");
    },
    onError: showError
  });

  const saveSpecMutation = useMutation({
    mutationFn: () =>
      api<SpecFile>(
        `/api/projects/${selectedProjectId}/spec`,
        asPut({ stage: specStage, content: editorValue })
      ),
    onSuccess: () => {
      setEditorDirty(false);
      invalidateProjectSlices(queryClient, selectedProjectId);
      setFlashMessage(`Saved ${specStage} spec`);
    },
    onError: showError
  });

  const generateMutation = useMutation({
    mutationFn: () => api(`/api/projects/${selectedProjectId}/generate`, { method: "POST" }),
    onSuccess: () => {
      setActiveStage("generation");
      invalidateProjectSlices(queryClient, selectedProjectId);
      setFlashMessage("Generation completed");
    },
    onError: showError
  });

  const driftMutation = useMutation({
    mutationFn: () => api(`/api/projects/${selectedProjectId}/drift`, { method: "POST" }),
    onSuccess: () => {
      setActiveStage("contracts");
      invalidateProjectSlices(queryClient, selectedProjectId);
      setFlashMessage("Drift report updated");
    },
    onError: showError
  });

  const specProposalMutation = useMutation({
    mutationFn: () =>
      api<SpecProposal>(
        `/api/projects/${selectedProjectId}/proposals/spec`,
        asJson({ instruction: proposalInstruction })
      ),
    onSuccess: (proposal) => {
      setProposalInstruction("");
      setActiveStage("spec");
      invalidateProjectSlices(queryClient, selectedProjectId);
      setSelectedProposalId(proposal.id);
      setFlashMessage("Spec proposal created");
    },
    onError: showError
  });

  const storyProposalMutation = useMutation({
    mutationFn: () =>
      api<StorySpecProposal>(`/api/projects/${selectedProjectId}/proposals/story`, asJson(storyForm)),
    onSuccess: (proposal) => {
      setStoryForm(STORY_DEFAULTS);
      setActiveStage("spec");
      invalidateProjectSlices(queryClient, selectedProjectId);
      setSelectedProposalId(proposal.id);
      setFlashMessage(`Story proposal created for ${proposal.title}`);
    },
    onError: showError
  });

  const applyProposalMutation = useMutation({
    mutationFn: (proposalId: string) =>
      api(`/api/projects/${selectedProjectId}/proposals/${proposalId}/apply`, { method: "POST" }),
    onSuccess: () => {
      setSpecStage("approved");
      setActiveStage("spec");
      invalidateProjectSlices(queryClient, selectedProjectId);
      setFlashMessage("Proposal applied to approved spec");
    },
    onError: showError
  });

  function handleNextAction() {
    setActiveStage(nextAction.stage);

    if (nextAction.command === "extract") {
      extractMutation.mutate();
      return;
    }

    if (nextAction.command === "build-draft") {
      buildDraftMutation.mutate();
    }
  }

  return {
    activeStage,
    setActiveStage,
    specStage,
    setSpecStage,
    selectedProjectId,
    selectedProject,
    hasProject,
    hasAnySpec,
    hasExtractionRun,
    hasDriftRun,
    hasGenerationRun,
    flashMessage,
    settingsQuery,
    projectsQuery,
    runsQuery,
    currentSpecQuery,
    approvedSpecQuery,
    draftSpecQuery,
    specProposalsQuery,
    storyProposalsQuery,
    extractionQuery,
    irQuery,
    contractDiffQuery,
    driftQuery,
    graphQuery,
    importForm,
    storyForm,
    proposalInstruction,
    setProposalInstruction,
    editorValue,
    editorDirty,
    validationReport,
    allProposals,
    reviewableProposals,
    selectedProposalId,
    selectedProposal,
    approvedSpecContent,
    draftSpecContent,
    proposalPatch,
    graphNodes,
    graphEdges,
    graphSummary,
    irPreviewJson,
    topFacts,
    topEvidence,
    topConflicts,
    topDriftItems,
    blockingDrift,
    contractDeltaCount,
    latestRun,
    currentBlocker,
    nextAction,
    stageSummaries,
    selectProject,
    updateEditor,
    updateImportField,
    updateStoryField,
    updateCapability,
    setSelectedProposalId,
    handleNextAction,
    importProjectMutation,
    extractMutation,
    buildDraftMutation,
    validateSpecMutation,
    saveSpecMutation,
    generateMutation,
    driftMutation,
    specProposalMutation,
    storyProposalMutation,
    applyProposalMutation
  };
}
