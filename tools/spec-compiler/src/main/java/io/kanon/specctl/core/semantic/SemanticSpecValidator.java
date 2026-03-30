package io.kanon.specctl.core.semantic;

import io.kanon.specctl.extraction.evidence.EvidenceSnapshot;
import io.kanon.specctl.semantic.SemanticSpecDocument;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SemanticSpecValidator {
    public SemanticSpecValidationResult validate(SemanticSpecDocument spec, EvidenceSnapshot evidenceSnapshot) {
        List<SemanticSpecValidationIssue> issues = new ArrayList<>();
        if (spec == null) {
            return new SemanticSpecValidationResult(
                    false,
                    List.of(new SemanticSpecValidationIssue("ERROR", "SPEC_EMPTY", "Semantic spec is missing", "/"))
            );
        }
        if (spec.service() == null || isBlank(spec.service().name())) {
            issues.add(new SemanticSpecValidationIssue("ERROR", "SERVICE_NAME_MISSING",
                    "Service name is required", "/service/name"));
        }

        Set<String> evidenceIds = new HashSet<>();
        if (evidenceSnapshot != null) {
            evidenceSnapshot.nodes().forEach(node -> evidenceIds.add(node.id()));
        }

        validateCitations("service", spec.service() == null ? List.of() : spec.service().evidence(), evidenceIds, issues);
        spec.interfaces().forEach(item -> validateItem(item.id(), "/interfaces", item.evidence(), evidenceIds, issues));
        spec.dataStores().forEach(item -> validateItem(item.id(), "/dataStores", item.evidence(), evidenceIds, issues));
        spec.integrations().forEach(item -> validateItem(item.id(), "/integrations", item.evidence(), evidenceIds, issues));
        spec.workflows().forEach(item -> validateItem(item.id(), "/workflows", item.evidence(), evidenceIds, issues));
        spec.rules().forEach(item -> validateItem(item.id(), "/rules", item.evidence(), evidenceIds, issues));
        spec.scenarios().forEach(item -> validateItem(item.id(), "/scenarios", item.evidence(), evidenceIds, issues));
        spec.notes().forEach(item -> validateItem(item.id(), "/notes", item.evidence(), evidenceIds, issues));

        return new SemanticSpecValidationResult(
                issues.stream().noneMatch(issue -> "ERROR".equals(issue.level())),
                issues
        );
    }

    private void validateItem(
            String id,
            String basePath,
            List<SemanticSpecDocument.EvidenceCitation> citations,
            Set<String> evidenceIds,
            List<SemanticSpecValidationIssue> issues
    ) {
        if (isBlank(id)) {
            issues.add(new SemanticSpecValidationIssue("ERROR", "ID_MISSING",
                    "Semantic item id is required", basePath));
        }
        validateCitations(id, citations, evidenceIds, issues);
    }

    private void validateCitations(
            String ownerId,
            List<SemanticSpecDocument.EvidenceCitation> citations,
            Set<String> evidenceIds,
            List<SemanticSpecValidationIssue> issues
    ) {
        if (citations == null || citations.isEmpty()) {
            issues.add(new SemanticSpecValidationIssue("ERROR", "EVIDENCE_REQUIRED",
                    "Semantic claims must include evidence citations", "/evidence/" + ownerId));
            return;
        }
        for (SemanticSpecDocument.EvidenceCitation citation : citations) {
            if (isBlank(citation.evidenceNodeId())) {
                issues.add(new SemanticSpecValidationIssue("ERROR", "EVIDENCE_NODE_MISSING",
                        "Citation must include an evidenceNodeId", "/evidence/" + ownerId));
                continue;
            }
            if (!evidenceIds.isEmpty() && !evidenceIds.contains(citation.evidenceNodeId())) {
                issues.add(new SemanticSpecValidationIssue("ERROR", "EVIDENCE_NODE_UNKNOWN",
                        "Citation references missing evidence node " + citation.evidenceNodeId(),
                        "/evidence/" + ownerId));
            }
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
