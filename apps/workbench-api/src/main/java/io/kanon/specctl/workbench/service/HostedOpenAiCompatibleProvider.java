package io.kanon.specctl.workbench.service;

import io.kanon.specctl.core.ai.LlmProvider;
import io.kanon.specctl.core.ai.ProposalRequest;
import io.kanon.specctl.workbench.config.WorkbenchProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Component
public class HostedOpenAiCompatibleProvider implements LlmProvider {
    private final WorkbenchProperties properties;
    private final WebClient webClient;

    public HostedOpenAiCompatibleProvider(WorkbenchProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClient = webClientBuilder.build();
    }

    @Override
    public String providerName() {
        return "hosted";
    }

    @Override
    public String defaultModel() {
        return properties.ai().hostedModel();
    }

    @Override
    public String proposeJson(ProposalRequest request) {
        if (properties.ai().hostedBaseUrl() == null || properties.ai().hostedApiKey() == null) {
            throw new IllegalStateException("Hosted AI provider is not configured");
        }
        Map<String, Object> payload = Map.of(
                "model", defaultModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", "Respond with JSON only matching the requested schema."),
                        Map.of("role", "user", "content", request.instruction() + "\nSchema: " + request.targetSchema() + "\nEvidence:\n" + String.join("\n", request.evidenceChunks()))
                ),
                "temperature", 0.0
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> response = webClient.post()
                .uri(properties.ai().hostedBaseUrl() + "/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.ai().hostedApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Map.class)
                .block();
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        Map<String, Object> message = (Map<String, Object>) choices.getFirst().get("message");
        return String.valueOf(message.get("content"));
    }
}
