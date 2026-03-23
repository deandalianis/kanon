import type { QueryClient } from "@tanstack/react-query";

export const workbenchKeys = {
  settings: ["settings"] as const,
  projects: ["projects"] as const,
  runs: (projectId: string) => ["runs", projectId] as const,
  spec: (projectId: string, stage: string) => ["spec", projectId, stage] as const,
  extraction: (projectId: string) => ["extraction", projectId] as const,
  drift: (projectId: string) => ["drift", projectId] as const,
  contracts: (projectId: string) => ["contracts", projectId] as const,
  graph: (projectId: string) => ["graph", projectId] as const,
  ir: (projectId: string) => ["ir", projectId] as const,
  specProposals: (projectId: string) => ["spec-proposals", projectId] as const,
  storyProposals: (projectId: string) => ["story-proposals", projectId] as const
};

export function invalidateProjectSlices(queryClient: QueryClient, projectId: string) {
  void queryClient.invalidateQueries({ queryKey: workbenchKeys.projects });

  const keys = [
    workbenchKeys.runs(projectId),
    workbenchKeys.spec(projectId, "current"),
    workbenchKeys.spec(projectId, "approved"),
    workbenchKeys.spec(projectId, "draft"),
    workbenchKeys.extraction(projectId),
    workbenchKeys.drift(projectId),
    workbenchKeys.contracts(projectId),
    workbenchKeys.graph(projectId),
    workbenchKeys.ir(projectId),
    workbenchKeys.specProposals(projectId),
    workbenchKeys.storyProposals(projectId)
  ];

  for (const queryKey of keys) {
    void queryClient.invalidateQueries({ queryKey });
  }
}
