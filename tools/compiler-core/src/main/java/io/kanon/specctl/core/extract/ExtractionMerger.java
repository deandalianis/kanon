package io.kanon.specctl.core.extract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ExtractionMerger {
    public ExtractionResult merge(ExtractionResult javaParserResult, ExtractionResult spoonResult) {
        Map<String, ExtractionResult.Fact> mergedFacts = new LinkedHashMap<>();
        List<ExtractionResult.Conflict> conflicts = new ArrayList<>();

        javaParserResult.facts().forEach(fact -> mergedFacts.put(key(fact), fact));
        for (ExtractionResult.Fact fact : spoonResult.facts()) {
            String key = key(fact);
            ExtractionResult.Fact existing = mergedFacts.get(key);
            if (existing == null) {
                mergedFacts.put(key, fact);
                continue;
            }
            if (!Objects.equals(existing.attributes(), fact.attributes())) {
                conflicts.add(new ExtractionResult.Conflict(
                        fact.path(),
                        "javaparser",
                        "spoon",
                        "Backend mismatch on extracted attributes",
                        false
                ));
            }
        }

        List<ExtractionResult.Provenance> provenance = new ArrayList<>();
        provenance.addAll(javaParserResult.provenance());
        provenance.addAll(spoonResult.provenance());

        List<ExtractionResult.Conflict> allConflicts = new ArrayList<>(conflicts);
        allConflicts.addAll(javaParserResult.conflicts());
        allConflicts.addAll(spoonResult.conflicts());

        double confidence = (javaParserResult.confidenceScore() + spoonResult.confidenceScore()) / 2.0d;
        return new ExtractionResult(List.copyOf(mergedFacts.values()), provenance, confidence, allConflicts);
    }

    private String key(ExtractionResult.Fact fact) {
        return fact.kind() + "::" + fact.path();
    }
}
