import {useMutation, useQuery, useQueryClient} from "@tanstack/react-query";
import {startTransition, useDeferredValue, useEffect, useMemo, useState} from "react";
import {api, asJson} from "../../api";
import type {
    BootstrapResponse,
    ExtractionSnapshot,
    GraphView,
    RunSummary,
    RuntimeSettings,
    SpecFile,
    WorkspaceRef
} from "../../types";
import {invalidateProjectSlices, workbenchKeys} from "./queryKeys";
import {
    IMPORT_DEFAULTS,
    type ImportFormState,
    type PipelineStage,
    PROJECT_STORAGE_KEY,
    type SpecStageKey
} from "./types";
import {
    buildStageSummaries,
    describeCurrentBlocker,
    deriveNextAction,
    errorMessage,
    hasSuccessfulRun,
    parseBootstrapMetadata,
    summarizeBootstrapRun,
    summarizeGraph
} from "./utils";

type ImportTextField = "sourcePath";

function persistProjectSelection(projectId: string) {
    localStorage.setItem(PROJECT_STORAGE_KEY, projectId);
}

function clearProjectSelection() {
    localStorage.removeItem(PROJECT_STORAGE_KEY);
}

export function useWorkbenchController() {
    const queryClient = useQueryClient();
    const [selectedProjectId, setSelectedProjectId] = useState<string>(() => localStorage.getItem(PROJECT_STORAGE_KEY) ?? "");
    const [activeStage, setActiveStage] = useState<PipelineStage>("evidence");
    const [specStage, setSpecStage] = useState<SpecStageKey>("current");
    const [importForm, setImportForm] = useState<ImportFormState>(IMPORT_DEFAULTS);
    const [flashMessage, setFlashMessage] = useState("");
    const [activeBootstrapRunId, setActiveBootstrapRunId] = useState("");
    const [bootstrapRunSnapshot, setBootstrapRunSnapshot] = useState<RunSummary | null>(null);

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
        enabled: hasProject,
        refetchInterval: hasProject && activeBootstrapRunId ? 2000 : false
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

    const hasAnySpec = Boolean(
        currentSpecQuery.data?.exists || approvedSpecQuery.data?.exists || draftSpecQuery.data?.exists
    );
    const hasApprovedSpec = Boolean(approvedSpecQuery.data?.exists);
    const hasExtractionRun = hasSuccessfulRun(runsQuery.data, "EXTRACTION");
    const hasSynthesisRun = hasSuccessfulRun(runsQuery.data, "SYNTHESIS");
    const hasGraphRun = hasSuccessfulRun(runsQuery.data, "GRAPH_REBUILD");

    const extractionQuery = useQuery({
        queryKey: workbenchKeys.extraction(selectedProjectId),
        queryFn: () => api<ExtractionSnapshot>(`/api/projects/${selectedProjectId}/artifacts/extraction`),
        enabled: hasProject && hasExtractionRun
    });

    const semanticSpecQuery = useQuery({
        queryKey: workbenchKeys.semanticSpec(selectedProjectId),
        queryFn: () => api<unknown>(`/api/projects/${selectedProjectId}/semantic-spec`),
        enabled: hasProject && hasAnySpec && (activeStage === "evidence" || activeStage === "graph")
    });

    const graphQuery = useQuery({
        queryKey: workbenchKeys.graph(selectedProjectId),
        queryFn: () => api<GraphView>(`/api/projects/${selectedProjectId}/graph/lineage`),
        enabled: hasProject && hasApprovedSpec && hasExtractionRun && activeStage === "graph"
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
            setActiveBootstrapRunId("");
            setBootstrapRunSnapshot(null);
            return;
        }

        const runningBootstrap = (runsQuery.data ?? []).find(
            (run) => run.kind === "BOOTSTRAP" && run.status === "RUNNING" && !run.parentRunId
        );

        if (runningBootstrap && runningBootstrap.id !== activeBootstrapRunId) {
            setActiveBootstrapRunId(runningBootstrap.id);
            setBootstrapRunSnapshot(runningBootstrap);
        }
    }, [activeBootstrapRunId, runsQuery.data, selectedProjectId]);

    useEffect(() => {
        if (!activeBootstrapRunId || !selectedProjectId) {
            return;
        }

        const trackedRun = (runsQuery.data ?? []).find((run) => run.id === activeBootstrapRunId) ?? null;
        if (!trackedRun) {
            return;
        }

        setBootstrapRunSnapshot(trackedRun);
        const progress = summarizeBootstrapRun(trackedRun);

        if (trackedRun.status === "RUNNING") {
            setFlashMessage(progress ? `Knowledge refresh: ${progress}` : "Knowledge refresh in progress");
            return;
        }

        setFlashMessage(
            trackedRun.status === "SUCCEEDED"
                ? "Knowledge refresh completed"
                : `Knowledge refresh failed: ${trackedRun.logText ?? "see run details"}`
        );
        setActiveBootstrapRunId("");
        invalidateProjectSlices(queryClient, selectedProjectId);
    }, [activeBootstrapRunId, queryClient, runsQuery.data, selectedProjectId]);

    const latestBootstrapRun = useMemo(() => {
        if (bootstrapRunSnapshot && (!selectedProjectId || bootstrapRunSnapshot.projectId === selectedProjectId)) {
            return bootstrapRunSnapshot;
        }

        return (runsQuery.data ?? []).find((run) => run.kind === "BOOTSTRAP" && !run.parentRunId) ?? null;
    }, [bootstrapRunSnapshot, runsQuery.data, selectedProjectId]);
    const latestSynthesisRun = useMemo(
        () => (runsQuery.data ?? []).find((run) => run.kind === "SYNTHESIS") ?? null,
        [runsQuery.data]
    );

    const deferredSemanticSpec = useDeferredValue(semanticSpecQuery.data);
    const latestRun = runsQuery.data?.[0] ?? latestBootstrapRun ?? null;
    const currentSpecContent = currentSpecQuery.data?.content ?? "";
    const approvedSpecContent = approvedSpecQuery.data?.content ?? "";
    const draftSpecContent = draftSpecQuery.data?.content ?? "";
    const graphSummary = summarizeGraph(graphQuery.data);
    const neo4jConfigured = settingsQuery.data?.neo4jConfigured ?? false;
    const currentBlocker = describeCurrentBlocker({
        hasExtractionRun,
        hasApprovedSpec,
        hasGraphRun,
        neo4jConfigured,
        extraction: extractionQuery.data,
        latestBootstrapRun
    });
    const nextAction = deriveNextAction({
        hasExtractionRun,
        hasApprovedSpec,
        hasGraphRun,
        neo4jConfigured,
        extraction: extractionQuery.data,
        latestBootstrapRun
    });
    const stageSummaries = buildStageSummaries({
        hasProject,
        hasApprovedSpec,
        hasExtractionRun,
        hasGraphRun,
        neo4jConfigured,
        extraction: extractionQuery.data,
        graphSummary,
        latestBootstrapRun
    });
    const irPreviewJson = deferredSemanticSpec ? JSON.stringify(deferredSemanticSpec, null, 2) : "";
    const bootstrapMetadata = parseBootstrapMetadata(latestBootstrapRun?.metadataJson);

    function selectProject(projectId: string) {
        startTransition(() => {
            setSelectedProjectId(projectId);
            persistProjectSelection(projectId);
            setActiveStage("evidence");
            setSpecStage("current");
        });
    }

    function updateImportField(field: ImportTextField, value: string) {
        setImportForm((current) => ({
            ...current,
            [field]: value
        }));
    }

    const importProjectMutation = useMutation({
        mutationFn: () => {
            const sourcePath = importForm.sourcePath.trim();
            if (!sourcePath) {
                throw new Error("Source path is required");
            }
            return api<BootstrapResponse>("/api/projects/import", asJson({sourcePath}));
        },
        onSuccess: (response) => {
            startTransition(() => {
                setSelectedProjectId(response.workspace.id);
                persistProjectSelection(response.workspace.id);
                setActiveStage("evidence");
            });
            setImportForm(IMPORT_DEFAULTS);
            setActiveBootstrapRunId(response.bootstrapRunId);
            setBootstrapRunSnapshot(null);
            setFlashMessage(`Imported ${response.workspace.name}. Knowledge bootstrap started.`);
            void queryClient.invalidateQueries({queryKey: workbenchKeys.projects});
            invalidateProjectSlices(queryClient, response.workspace.id);
        },
        onError: (error) => {
            setFlashMessage(`Import failed: ${errorMessage(error)}`);
        }
    });

    const refreshKnowledgeMutation = useMutation({
        mutationFn: () => api<BootstrapResponse>(`/api/projects/${selectedProjectId}/refresh`, {method: "POST"}),
        onSuccess: (response) => {
            setActiveBootstrapRunId(response.bootstrapRunId);
            setBootstrapRunSnapshot(null);
            setActiveStage("evidence");
            invalidateProjectSlices(queryClient, selectedProjectId);
            setFlashMessage("Knowledge refresh started");
        },
        onError: (error) => {
            setFlashMessage(`Refresh failed: ${errorMessage(error)}`);
        }
    });

    function handleNextAction() {
        setActiveStage(nextAction.stage);

        if (nextAction.command === "refresh") {
            refreshKnowledgeMutation.mutate();
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
        hasApprovedSpec,
        hasExtractionRun,
        hasSynthesisRun,
        hasGraphRun,
        flashMessage,
        settingsQuery,
        projectsQuery,
        runsQuery,
        currentSpecQuery,
        approvedSpecQuery,
        draftSpecQuery,
        extractionQuery,
        semanticSpecQuery,
        graphQuery,
        importForm,
        currentSpecContent,
        approvedSpecContent,
        draftSpecContent,
        graphSummary,
        irPreviewJson,
        latestRun,
        latestBootstrapRun,
        latestSynthesisRun,
        bootstrapMetadata,
        currentBlocker,
        nextAction,
        stageSummaries,
        selectProject,
        updateImportField,
        handleNextAction,
        importProjectMutation,
        refreshKnowledgeMutation
    };
}
