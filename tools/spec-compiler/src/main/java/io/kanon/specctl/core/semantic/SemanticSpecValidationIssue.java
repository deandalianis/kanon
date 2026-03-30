package io.kanon.specctl.core.semantic;

public record SemanticSpecValidationIssue(
        String level,
        String code,
        String message,
        String path
) {
}
