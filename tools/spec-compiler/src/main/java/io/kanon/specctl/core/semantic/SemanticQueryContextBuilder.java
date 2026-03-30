package io.kanon.specctl.core.semantic;

import io.kanon.specctl.extraction.evidence.EvidenceNode;
import io.kanon.specctl.extraction.evidence.EvidenceSnapshot;
import io.kanon.specctl.semantic.SemanticSpecDocument;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class SemanticQueryContextBuilder {
    public List<String> build(SemanticSpecDocument spec, EvidenceSnapshot evidenceSnapshot, String question, int topK) {
        List<String> docs = new ArrayList<>();
        docs.add(serialize(spec.service()));
        spec.interfaces().forEach(item -> docs.add(serialize(item)));
        spec.dataStores().forEach(item -> docs.add(serialize(item)));
        spec.integrations().forEach(item -> docs.add(serialize(item)));
        spec.workflows().forEach(item -> docs.add(serialize(item)));
        spec.rules().forEach(item -> docs.add(serialize(item)));
        spec.scenarios().forEach(item -> docs.add(serialize(item)));
        evidenceSnapshot.nodes().stream()
                .filter(node -> "documentation-section".equals(node.kind())
                        || "contract-operation".equals(node.kind())
                        || "contract-channel".equals(node.kind()))
                .map(this::serialize)
                .forEach(docs::add);
        return rank(docs, question, topK);
    }

    private List<String> rank(List<String> docs, String question, int topK) {
        List<String> terms = tokenize(question);
        if (terms.isEmpty()) {
            return docs.stream().limit(topK).toList();
        }
        return docs.stream()
                .map(doc -> new Ranked(doc, score(doc, terms)))
                .sorted(Comparator.comparingInt(Ranked::score).reversed())
                .limit(topK)
                .map(Ranked::doc)
                .toList();
    }

    private int score(String doc, List<String> terms) {
        String haystack = doc.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (haystack.contains(term)) {
                score++;
            }
        }
        return score;
    }

    private List<String> tokenize(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(token -> token.length() > 2)
                .distinct()
                .toList();
    }

    private String serialize(SemanticSpecDocument.Service service) {
        return "SERVICE " + service.name() + "\nSummary: " + service.summary();
    }

    private String serialize(SemanticSpecDocument.InterfacePoint item) {
        return "INTERFACE " + item.name() + "\nProtocol: " + item.protocol() + "\nOperations: " +
                item.operations().stream().map(SemanticSpecDocument.Operation::name).toList();
    }

    private String serialize(SemanticSpecDocument.DataStore item) {
        return "DATASTORE " + item.name() + "\nTechnology: " + item.technology() + "\nArtifacts: " + item.artifacts();
    }

    private String serialize(SemanticSpecDocument.Integration item) {
        return "INTEGRATION " + item.name() + "\nKind: " + item.kind() + "\nArtifacts: " + item.artifacts();
    }

    private String serialize(SemanticSpecDocument.Workflow item) {
        return "WORKFLOW " + item.name() + "\nSummary: " + item.summary();
    }

    private String serialize(SemanticSpecDocument.Rule item) {
        return "RULE " + item.name() + "\nStatement: " + item.statement();
    }

    private String serialize(SemanticSpecDocument.Scenario item) {
        return "SCENARIO " + item.name() + "\nThen: " + item.then();
    }

    private String serialize(EvidenceNode node) {
        return node.kind().toUpperCase(Locale.ROOT) + " " + node.label() + "\nPath: " + node.path();
    }

    private record Ranked(String doc, int score) {
    }
}
