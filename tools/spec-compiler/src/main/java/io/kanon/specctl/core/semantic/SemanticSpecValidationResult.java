package io.kanon.specctl.core.semantic;

import java.util.List;

public record SemanticSpecValidationResult(
        boolean valid,
        List<SemanticSpecValidationIssue> issues
) {
    public SemanticSpecValidationResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
