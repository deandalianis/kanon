package io.kanon.specctl.workbench.service;

import io.kanon.specctl.core.ai.LlmProvider;
import io.kanon.specctl.workbench.config.WorkbenchProperties;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class LlmProviderRouter {
    private final Map<String, LlmProvider> providers;
    private final WorkbenchProperties properties;

    public LlmProviderRouter(java.util.List<LlmProvider> providers, WorkbenchProperties properties) {
        this.providers = providers.stream().collect(Collectors.toMap(LlmProvider::providerName, Function.identity()));
        this.properties = properties;
    }

    public LlmProvider activeProvider() {
        return providers.getOrDefault(properties.ai().provider(), providers.get("heuristic"));
    }
}
