package io.kanon.specctl.workbench.service;

import io.kanon.specctl.core.ai.LlmProvider;
import io.kanon.specctl.core.ai.ProposalRequest;
import io.kanon.specctl.workbench.config.WorkbenchProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Component
public class OllamaProvider implements LlmProvider {
    private final WorkbenchProperties properties;
    private final WebClient webClient;

    public OllamaProvider(WorkbenchProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClient = webClientBuilder.build();
    }

    @Override
    public String providerName() {
        return "ollama";
    }

    @Override
    public String defaultModel() {
        return properties.ai().ollamaModel();
    }

    @Override
    public String proposeJson(ProposalRequest request) {
        if (properties.ai().ollamaBaseUrl() == null) {
            throw new IllegalStateException("Ollama provider is not configured");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> response = webClient.post()
                .uri(properties.ai().ollamaBaseUrl() + "/api/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", defaultModel(),
                        "stream", false,
                        "prompt", "Respond with JSON only. " + request.instruction() + "\nSchema: " + request.targetSchema() + "\nEvidence:\n" + String.join("\n", request.evidenceChunks())
                ))
                .retrieve()
                .bodyToMono(Map.class)
                .block();
        return String.valueOf(response.get("response"));
    }
}
