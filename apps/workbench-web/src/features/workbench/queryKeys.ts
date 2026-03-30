import type {QueryClient} from "@tanstack/react-query";

export const workbenchKeys = {
    settings: ["settings"] as const,
    projects: ["projects"] as const,
    runs: (projectId: string) => ["runs", projectId] as const,
    spec: (projectId: string, stage: string) => ["spec", projectId, stage] as const,
    extraction: (projectId: string) => ["extraction", projectId] as const,
    graph: (projectId: string) => ["graph", projectId] as const,
    semanticSpec: (projectId: string) => ["semantic-spec", projectId] as const
};

export function invalidateProjectSlices(queryClient: QueryClient, projectId: string) {
    void queryClient.invalidateQueries({queryKey: workbenchKeys.projects});

    const keys = [
        workbenchKeys.runs(projectId),
        workbenchKeys.spec(projectId, "current"),
        workbenchKeys.spec(projectId, "approved"),
        workbenchKeys.spec(projectId, "draft"),
        workbenchKeys.extraction(projectId),
        workbenchKeys.graph(projectId),
        workbenchKeys.semanticSpec(projectId)
    ];

    for (const queryKey of keys) {
        void queryClient.invalidateQueries({queryKey});
    }
}
