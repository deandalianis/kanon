package io.kanon.specctl.workbench.service;

import io.kanon.specctl.core.ai.ProposalRequest;
import io.kanon.specctl.core.extract.ExtractionResult;
import io.kanon.specctl.dsl.SpecDocument;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class BddScenarioExtractor {

    private static final String BDD_SCHEMA = """
            {
              "scenarios": [
                {
                  "name": "string (scenario name describing the behaviour)",
                  "given": [
                    {
                      "step": "string (human-readable precondition)",
                      "impl": {
                        "type": "assert|set|emit|call|throw|condition|condition_else|null_check|collection_check",
                        "expr": "optional string for assert/null_check/collection_check",
                        "message": "optional string for assert/throw",
                        "target": "optional string for set",
                        "value": "optional string for set",
                        "service": "optional string for call",
                        "method": "optional string for call",
                        "args": ["optional list of strings for call"],
                        "event": "optional string for emit",
                        "when": "optional string for condition/condition_else",
                        "then": ["optional nested impl steps"],
                        "els": ["optional nested impl steps for condition_else"]
                      },
                      "sourceHint": "optional: original Java source snippet for this step if impl cannot express it"
                    }
                  ],
                  "when": [
                    {
                      "step": "string (human-readable trigger, e.g. 'ProcessOrder is called with promoCode')"
                    }
                  ],
                  "then": [
                    {
                      "step": "string (human-readable outcome)",
                      "impl": { "...same structure as above..." },
                      "sourceHint": "optional: original Java source snippet if impl cannot express it"
                    }
                  ]
                }
              ]
            }
            """;

    private static final String INSTRUCTION_TEMPLATE = """
            You are a BDD scenario extractor. Analyse the provided Java method and extract BDD scenarios.
            
            Rules:
            1. Extract one scenario per logical code path (happy path + error paths).
            2. For each step, provide BOTH a human-readable 'step' string AND a structured 'impl' block.
            3. Use the 'impl' vocabulary types: assert, set, emit, call, throw, condition, condition_else, null_check, collection_check.
            4. Prefer impl types over sourceHint. Only use sourceHint if the logic genuinely cannot be expressed using the impl vocabulary.
            5. For 'given' steps: capture preconditions (state assertions, parameter checks).
            6. For 'when' steps: describe the command trigger with its key inputs.
            7. For 'then' steps: capture state transitions, service calls, event emissions, exceptions.
            8. Keep step text concise and domain-focused (no Java syntax in step text).
            9. For sourceHint: include the EXACT minimal Java source snippet that implements the step.
            
            Method to analyse:
            Method name: %s
            Class: %s
            Method signature: %s
            Method body:
            %s
            """;

    private final LlmProviderRouter llmProviderRouter;

    public BddScenarioExtractor(LlmProviderRouter llmProviderRouter) {
        this.llmProviderRouter = llmProviderRouter;
    }

    public List<SpecDocument.BddScenario> extract(ExtractionResult.Fact methodFact, String className) {
        Object methodBody = methodFact.attributes().get("methodBody");
        if (methodBody == null || methodBody.toString().isBlank()) {
            return List.of();
        }

        String methodName = String.valueOf(methodFact.attributes().getOrDefault("name", "unknown"));
        String returnType = String.valueOf(methodFact.attributes().getOrDefault("returnType", "void"));
        String parameters = formatParameters(methodFact.attributes().get("parameters"));
        String signature = returnType + " " + methodName + "(" + parameters + ")";

        String instruction = INSTRUCTION_TEMPLATE.formatted(
                methodName, className, signature, methodBody.toString()
        );

        ProposalRequest request = new ProposalRequest(
                instruction,
                BDD_SCHEMA,
                List.of(methodBody.toString()),
                Map.of("methodName", methodName, "className", className)
        );

        try {
            String json = llmProviderRouter.activeProvider().proposeJson(request);
            return parseScenarios(json);
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<SpecDocument.BddScenario> parseScenarios(String json) {
        try {
            Map<String, Object> parsed = JsonCodec.read(json, Map.class);
            Object rawScenarios = parsed.get("scenarios");
            if (!(rawScenarios instanceof List<?> scenarioList)) {
                return List.of();
            }
            List<SpecDocument.BddScenario> result = new ArrayList<>();
            for (Object rawScenario : scenarioList) {
                if (rawScenario instanceof Map<?, ?> scenarioMap) {
                    result.add(parseScenario((Map<String, Object>) scenarioMap));
                }
            }
            return List.copyOf(result);
        } catch (Exception e) {
            return List.of();
        }
    }

    private SpecDocument.BddScenario parseScenario(Map<String, Object> map) {
        String name = String.valueOf(map.getOrDefault("name", "Scenario"));
        List<SpecDocument.BddStep> given = parseSteps(map.get("given"));
        List<SpecDocument.BddStep> when = parseSteps(map.get("when"));
        List<SpecDocument.BddStep> then = parseSteps(map.get("then"));
        return new SpecDocument.BddScenario(name, given, when, then);
    }

    @SuppressWarnings("unchecked")
    private List<SpecDocument.BddStep> parseSteps(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<SpecDocument.BddStep> steps = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> stepMap) {
                Map<String, Object> m = (Map<String, Object>) stepMap;
                String step = String.valueOf(m.getOrDefault("step", ""));
                SpecDocument.ImplStep impl = m.get("impl") instanceof Map<?, ?> implMap
                        ? parseImplStep((Map<String, Object>) implMap)
                        : null;
                String sourceHint = m.get("sourceHint") instanceof String s ? s : null;
                steps.add(new SpecDocument.BddStep(step, impl, sourceHint));
            }
        }
        return List.copyOf(steps);
    }

    @SuppressWarnings("unchecked")
    private SpecDocument.ImplStep parseImplStep(Map<String, Object> map) {
        String type = String.valueOf(map.getOrDefault("type", "call"));
        String expr = map.get("expr") instanceof String s ? s : null;
        String message = map.get("message") instanceof String s ? s : null;
        String target = map.get("target") instanceof String s ? s : null;
        String value = map.get("value") instanceof String s ? s : null;
        String service = map.get("service") instanceof String s ? s : null;
        String method = map.get("method") instanceof String s ? s : null;
        List<String> args = map.get("args") instanceof List<?> l
                ? l.stream().map(Object::toString).toList()
                : List.of();
        String event = map.get("event") instanceof String s ? s : null;
        String when = map.get("when") instanceof String s ? s : null;
        List<SpecDocument.ImplStep> then = map.get("then") instanceof List<?> l
                ? l.stream()
                .filter(i -> i instanceof Map<?, ?>)
                .map(i -> parseImplStep((Map<String, Object>) i))
                .toList()
                : List.of();
        List<SpecDocument.ImplStep> els = map.get("els") instanceof List<?> l
                ? l.stream()
                .filter(i -> i instanceof Map<?, ?>)
                .map(i -> parseImplStep((Map<String, Object>) i))
                .toList()
                : List.of();
        return new SpecDocument.ImplStep(type, expr, message, target, value, service, method, args, event, when, then, els);
    }

    @SuppressWarnings("unchecked")
    private String formatParameters(Object rawParams) {
        if (!(rawParams instanceof List<?> params)) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Object p : params) {
            if (p instanceof Map<?, ?>) {
                Map<String, Object> m = (Map<String, Object>) p;
                String type = String.valueOf(m.getOrDefault("type", "Object"));
                String name = String.valueOf(m.getOrDefault("name", "param"));
                parts.add(type + " " + name);
            }
        }
        return String.join(", ", parts);
    }
}
