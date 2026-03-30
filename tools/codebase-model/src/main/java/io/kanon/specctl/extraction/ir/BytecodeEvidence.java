package io.kanon.specctl.extraction.ir;

import java.util.List;

public record BytecodeEvidence(
        List<CodebaseIr.Type> types,
        List<CodebaseIr.Endpoint> endpoints,
        List<CodebaseIr.Bean> beans,
        List<CodebaseIr.JpaEntity> jpaEntities,
        List<CodebaseIr.ValidationConstraint> validations,
        List<CodebaseIr.SecurityConstraint> securities,
        List<EvidenceConflict> conflicts,
        List<Provenance> provenance,
        List<String> diagnostics
) {
    public BytecodeEvidence {
        types = immutable(types);
        endpoints = immutable(endpoints);
        beans = immutable(beans);
        jpaEntities = immutable(jpaEntities);
        validations = immutable(validations);
        securities = immutable(securities);
        conflicts = immutable(conflicts);
        provenance = immutable(provenance);
        diagnostics = immutable(diagnostics);
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
