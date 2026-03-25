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
        String name = properties.ai().provider();
        LlmProvider provider = name != null ? providers.get(name) : null;
        if (provider == null) {
            throw new IllegalStateException(
                    "No AI provider configured. Set KANON_AI_PROVIDER=ollama or KANON_AI_PROVIDER=hosted.");
        }
        return provider;
    }
}
