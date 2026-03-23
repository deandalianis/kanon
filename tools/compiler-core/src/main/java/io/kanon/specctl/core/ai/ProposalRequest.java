package io.kanon.specctl.core.ai;

import io.kanon.specctl.core.util.MoreCollections;

import java.util.List;
import java.util.Map;

public record ProposalRequest(
        String instruction,
        String targetSchema,
        List<String> evidenceChunks,
        Map<String, Object> context
) {
    public ProposalRequest {
        evidenceChunks = MoreCollections.immutableList(evidenceChunks);
        context = MoreCollections.immutableMap(context);
    }
}
