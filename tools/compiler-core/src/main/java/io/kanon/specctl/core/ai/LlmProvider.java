package io.kanon.specctl.core.ai;

public interface LlmProvider {
    String providerName();

    String defaultModel();

    String proposeJson(ProposalRequest request);
}
