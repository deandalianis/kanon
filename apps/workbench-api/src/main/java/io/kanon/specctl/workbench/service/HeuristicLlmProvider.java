package io.kanon.specctl.workbench.service;

import io.kanon.specctl.core.ai.LlmProvider;
import io.kanon.specctl.core.ai.ProposalRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class HeuristicLlmProvider implements LlmProvider {
    @Override
    public String providerName() {
        return "heuristic";
    }

    @Override
    public String defaultModel() {
        return "deterministic-heuristic";
    }

    @Override
    public String proposeJson(ProposalRequest request) {
        String title = String.valueOf(request.context().getOrDefault("title", "Generated proposal"));
        return JsonCodec.write(Map.of(
                "title", title,
                "summary", request.instruction(),
                "specPatch", request.context().getOrDefault("specPatch", ""),
                "migrationHints", List.of("Review generated patch", "Approve and regenerate"),
                "contractImpacts", List.of("OpenAPI may gain endpoints", "Event contracts may change if messaging is enabled"),
                "acceptanceTests", List.of("Validate generated spec", "Compile regenerated project"),
                "evidencePaths", request.evidenceChunks()
        ));
    }
}
