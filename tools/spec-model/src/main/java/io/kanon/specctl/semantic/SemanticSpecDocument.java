package io.kanon.specctl.semantic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SemanticSpecDocument(
        int schemaVersion,
        String specVersion,
        Service service,
        List<InterfacePoint> interfaces,
        List<DataStore> dataStores,
        List<Integration> integrations,
        List<Workflow> workflows,
        List<Rule> rules,
        List<Scenario> scenarios,
        List<SemanticNote> notes
) {
    public SemanticSpecDocument {
        interfaces = immutableList(interfaces);
        dataStores = immutableList(dataStores);
        integrations = immutableList(integrations);
        workflows = immutableList(workflows);
        rules = immutableList(rules);
        scenarios = immutableList(scenarios);
        notes = immutableList(notes);
    }

    private static <T> List<T> immutableList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> values) {
        return values == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(values));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Service(
            String id,
            String name,
            String basePackage,
            String summary,
            List<EvidenceCitation> evidence,
            Confidence confidence,
            Map<String, String> metadata
    ) {
        public Service {
            evidence = immutableList(evidence);
            metadata = immutableMap(metadata);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InterfacePoint(
            String id,
            String name,
            String kind,
            String protocol,
            String location,
            String summary,
            List<Operation> operations,
            List<EvidenceCitation> evidence,
            Confidence confidence
    ) {
        public InterfacePoint {
            operations = immutableList(operations);
            evidence = immutableList(evidence);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Operation(
            String id,
            String name,
            String method,
            String pathOrChannel,
            String summary,
            List<EvidenceCitation> evidence,
            Confidence confidence
    ) {
        public Operation {
            evidence = immutableList(evidence);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DataStore(
            String id,
            String name,
            String technology,
            String summary,
            List<String> artifacts,
            List<EvidenceCitation> evidence,
            Confidence confidence
    ) {
        public DataStore {
            artifacts = immutableList(artifacts);
            evidence = immutableList(evidence);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Integration(
            String id,
            String name,
            String kind,
            String direction,
            String target,
            String summary,
            List<String> artifacts,
            List<EvidenceCitation> evidence,
            Confidence confidence
    ) {
        public Integration {
            artifacts = immutableList(artifacts);
            evidence = immutableList(evidence);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Workflow(
            String id,
            String name,
            String summary,
            List<WorkflowStep> steps,
            List<EvidenceCitation> evidence,
            Confidence confidence
    ) {
        public Workflow {
            steps = immutableList(steps);
            evidence = immutableList(evidence);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WorkflowStep(
            String id,
            String kind,
            String description,
            List<EvidenceCitation> evidence
    ) {
        public WorkflowStep {
            evidence = immutableList(evidence);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Rule(
            String id,
            String name,
            String category,
            String statement,
            List<EvidenceCitation> evidence,
            Confidence confidence
    ) {
        public Rule {
            evidence = immutableList(evidence);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Scenario(
            String id,
            String name,
            List<String> given,
            List<String> when,
            List<String> then,
            List<EvidenceCitation> evidence,
            Confidence confidence
    ) {
        public Scenario {
            given = immutableList(given);
            when = immutableList(when);
            then = immutableList(then);
            evidence = immutableList(evidence);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SemanticNote(
            String id,
            String kind,
            String title,
            String detail,
            List<EvidenceCitation> evidence,
            Confidence confidence
    ) {
        public SemanticNote {
            evidence = immutableList(evidence);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EvidenceCitation(
            String evidenceNodeId,
            String file,
            Integer startLine,
            Integer endLine,
            String rationale
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Confidence(
            String level,
            String rationale
    ) {
    }
}
