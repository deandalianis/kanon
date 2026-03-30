package io.kanon.specctl.workbench.service;

import io.kanon.specctl.core.ai.LlmProvider;
import io.kanon.specctl.core.ai.ProposalRequest;
import io.kanon.specctl.workbench.config.WorkbenchProperties;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class OllamaProvider implements LlmProvider {
    private final WorkbenchProperties properties;
    private final WebClient webClient;

    public OllamaProvider(WorkbenchProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClient = webClientBuilder
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                        .build())
                .build();
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
        String body = webClient.post()
                .uri(properties.ai().ollamaBaseUrl() + "/api/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", defaultModel(),
                        "stream", false,
                        "format", "json",
                        "num_ctx", 32768,
                        "prompt", request.instruction() + "\nEvidence:\n" + String.join("\n", request.evidenceChunks())
                ))
                .retrieve()
                .bodyToMono(String.class)
                .block();
        return extractGeneratedResponse(body);
    }

    @Override
    public String proposeText(ProposalRequest request) {
        if (properties.ai().ollamaBaseUrl() == null) {
            throw new IllegalStateException("Ollama provider is not configured");
        }
        String context = String.join("\n\n", request.evidenceChunks());
        String prompt =
                "You are a domain expert on this service spec. Answer the following question using only the provided spec context.\n\nContext:\n" +
                        context + "\n\nQuestion: " + request.instruction();
        String body = webClient.post()
                .uri(properties.ai().ollamaBaseUrl() + "/api/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", defaultModel(),
                        "stream", false,
                        "prompt", prompt
                ))
                .retrieve()
                .bodyToMono(String.class)
                .block();
        return extractGeneratedResponse(body);
    }

    @SuppressWarnings("unchecked")
    private String extractGeneratedResponse(String body) {
        try {
            Map<String, Object> response = JsonCodec.read(body, Map.class);
            Object generated = response.get("response");
            if (generated == null) {
                throw new IllegalStateException("Ollama response did not contain a 'response' field. Body: "
                        + excerpt(body));
            }
            return String.valueOf(generated);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse Ollama response: "
                    + exception.getMessage() + ". Body: " + excerpt(body), exception);
        }
    }

    private String excerpt(String body) {
        if (body == null || body.isBlank()) {
            return "<empty>";
        }
        String singleLine = body.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= 320 ? singleLine : singleLine.substring(0, 320) + "...";
    }
}
