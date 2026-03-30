package io.kanon.specctl.workbench.service;

import io.kanon.specctl.ir.CanonicalIr;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

final class IrContextBuilder {

    private static final double K1 = 1.5;
    private static final double B = 0.75;

    List<String> buildAllContext(CanonicalIr ir, String question) {
        List<AggregateDoc> docs = new ArrayList<>();
        for (CanonicalIr.BoundedContext bc : ir.boundedContexts()) {
            for (CanonicalIr.Aggregate agg : bc.aggregates()) {
                docs.add(new AggregateDoc(agg.name(), serialize(agg)));
            }
        }
        if (docs.isEmpty()) {
            return List.of();
        }

        List<String> queryTerms = tokenize(question);
        if (queryTerms.isEmpty()) {
            return docs.stream().map(AggregateDoc::text).toList();
        }

        List<List<String>> tokenizedDocs = docs.stream()
                .map(d -> tokenize(d.text()))
                .toList();
        double avgDl = tokenizedDocs.stream().mapToInt(List::size).average().orElse(1.0);

        Map<String, Integer> df = new HashMap<>();
        for (String term : new HashSet<>(queryTerms)) {
            int count = 0;
            for (List<String> docTokens : tokenizedDocs) {
                if (docTokens.contains(term)) {
                    count++;
                }
            }
            df.put(term, count);
        }

        int n = docs.size();
        List<ScoredDoc> scored = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            List<String> docTokens = tokenizedDocs.get(i);
            int dl = docTokens.size();
            double score = 0.0;
            for (String term : queryTerms) {
                long tf = docTokens.stream().filter(term::equals).count();
                if (tf == 0) {
                    continue;
                }
                int docFreq = df.getOrDefault(term, 0);
                double idf = Math.log((n - docFreq + 0.5) / (docFreq + 0.5) + 1.0);
                double tfNorm = (tf * (K1 + 1.0)) / (tf + K1 * (1.0 - B + B * dl / avgDl));
                score += idf * tfNorm;
            }
            scored.add(new ScoredDoc(docs.get(i).text(), score));
        }

        return scored.stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .map(ScoredDoc::text)
                .toList();
    }

    List<String> buildContext(CanonicalIr ir, String question, int topK) {
        List<AggregateDoc> docs = new ArrayList<>();
        for (CanonicalIr.BoundedContext bc : ir.boundedContexts()) {
            for (CanonicalIr.Aggregate agg : bc.aggregates()) {
                docs.add(new AggregateDoc(agg.name(), serialize(agg)));
            }
        }
        if (docs.isEmpty()) {
            return List.of();
        }

        List<String> queryTerms = tokenize(question);
        if (queryTerms.isEmpty()) {
            return docs.stream().limit(topK).map(AggregateDoc::text).toList();
        }

        List<List<String>> tokenizedDocs = docs.stream()
                .map(d -> tokenize(d.text()))
                .toList();
        double avgDl = tokenizedDocs.stream().mapToInt(List::size).average().orElse(1.0);

        Map<String, Integer> df = new HashMap<>();
        for (String term : new HashSet<>(queryTerms)) {
            int count = 0;
            for (List<String> docTokens : tokenizedDocs) {
                if (docTokens.contains(term)) {
                    count++;
                }
            }
            df.put(term, count);
        }

        int n = docs.size();
        List<ScoredDoc> scored = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            List<String> docTokens = tokenizedDocs.get(i);
            int dl = docTokens.size();
            double score = 0.0;
            for (String term : queryTerms) {
                long tf = docTokens.stream().filter(term::equals).count();
                if (tf == 0) {
                    continue;
                }
                int docFreq = df.getOrDefault(term, 0);
                double idf = Math.log((n - docFreq + 0.5) / (docFreq + 0.5) + 1.0);
                double tfNorm = (tf * (K1 + 1.0)) / (tf + K1 * (1.0 - B + B * dl / avgDl));
                score += idf * tfNorm;
            }
            scored.add(new ScoredDoc(docs.get(i).text(), score));
        }

        List<String> result = scored.stream()
                .filter(s -> s.score() > 0)
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(topK)
                .map(ScoredDoc::text)
                .toList();

        return result.isEmpty() ? docs.stream().limit(topK).map(AggregateDoc::text).toList() : result;
    }

    private String serialize(CanonicalIr.Aggregate agg) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Aggregate: ").append(agg.name()).append("]\n");
        if (agg.identity() != null) {
            sb.append("Identity: ").append(agg.identity().field())
                    .append(" (").append(agg.identity().type()).append(")\n");
        }
        if (!agg.commands().isEmpty()) {
            sb.append("Commands:\n");
            for (CanonicalIr.Command cmd : agg.commands()) {
                sb.append("  ").append(cmd.name());
                if (cmd.http() != null) {
                    sb.append(" [").append(cmd.http().method()).append(" ").append(cmd.http().path()).append("]");
                }
                if (!cmd.rules().isEmpty()) {
                    String ruleIds = cmd.rules().stream().map(CanonicalIr.Rule::id).collect(Collectors.joining(", "));
                    sb.append(" rules=[").append(ruleIds).append("]");
                }
                if (!cmd.scenarios().isEmpty()) {
                    String names = cmd.scenarios().stream().map(CanonicalIr.BddScenario::name)
                            .collect(Collectors.joining("; "));
                    sb.append(" scenarios=[").append(names).append("]");
                }
                sb.append("\n");
            }
        }
        if (!agg.entities().isEmpty()) {
            sb.append("Entities:\n");
            for (CanonicalIr.Entity entity : agg.entities()) {
                String fields = entity.fields().stream()
                        .map(f -> f.name() + "(" + f.type() + ")")
                        .collect(Collectors.joining(", "));
                sb.append("  ").append(entity.name()).append(" [table=").append(entity.table()).append("]");
                if (!fields.isEmpty()) {
                    sb.append(" fields: ").append(fields);
                }
                sb.append("\n");
            }
        }
        if (!agg.events().isEmpty()) {
            sb.append("Events:\n");
            for (CanonicalIr.Event event : agg.events()) {
                sb.append("  ").append(event.name()).append(" [topic=").append(event.topic()).append("]\n");
            }
        }
        if (agg.stateMachine() != null) {
            sb.append("StateMachine: ").append(agg.stateMachine().name())
                    .append(" states=[").append(String.join(", ", agg.stateMachine().states())).append("]\n");
        }
        return sb.toString().trim();
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(t -> t.length() > 1)
                .toList();
    }

    private record AggregateDoc(String name, String text) {
    }

    private record ScoredDoc(String text, double score) {
    }
}
