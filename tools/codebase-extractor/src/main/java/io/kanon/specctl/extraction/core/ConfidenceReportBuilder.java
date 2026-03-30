package io.kanon.specctl.extraction.core;

import io.kanon.specctl.extraction.ir.BuildResolution;
import io.kanon.specctl.extraction.ir.BytecodeEvidence;
import io.kanon.specctl.extraction.ir.ConfidenceDomain;
import io.kanon.specctl.extraction.ir.ConfidenceReport;
import io.kanon.specctl.extraction.ir.DomainStatus;
import io.kanon.specctl.extraction.ir.MergedEvidence;
import io.kanon.specctl.extraction.ir.RuntimeEvidence;
import io.kanon.specctl.extraction.ir.SourceEvidence;
import java.util.ArrayList;
import java.util.List;

public final class ConfidenceReportBuilder {
    public ConfidenceReport build(
            BuildResolution buildResolution,
            SourceEvidence sourceEvidence,
            BytecodeEvidence bytecodeEvidence,
            RuntimeEvidence runtimeEvidence,
            MergedEvidence mergedEvidence
    ) {
        List<ConfidenceReport.DomainConfidence> domains = new ArrayList<>();
        domains.add(new ConfidenceReport.DomainConfidence(
                ConfidenceDomain.BUILD,
                buildResolution.buildSucceeded() ? DomainStatus.CONFIRMED : DomainStatus.MISSING,
                buildResolution.buildSucceeded() ? "Build resolution succeeded" : "Build resolution failed",
                buildResolution.diagnostics()
        ));
        domains.add(domain(ConfidenceDomain.TYPES,
                mergedEvidence.types().isEmpty() ? DomainStatus.MISSING : DomainStatus.CONFIRMED,
                "Merged types extracted", mergedEvidence.diagnostics()));
        boolean methodBodiesPresent = mergedEvidence.types().stream()
                .flatMap(type -> type.methods().stream())
                .anyMatch(method -> method.body() != null && method.body().normalizedSource() != null);
        domains.add(domain(ConfidenceDomain.METHOD_BODIES,
                methodBodiesPresent ? DomainStatus.CONFIRMED : DomainStatus.PARTIAL,
                "Method body evidence derived from source extraction", sourceEvidence.diagnostics()));
        boolean callGraphPresent = mergedEvidence.types().stream()
                .flatMap(type -> type.methods().stream())
                .anyMatch(method -> method.body() != null && !method.body().callEdges().isEmpty());
        domains.add(
                domain(ConfidenceDomain.CALL_GRAPH, callGraphPresent ? DomainStatus.CONFIRMED : DomainStatus.PARTIAL,
                        "Call edges extracted from method bodies", sourceEvidence.diagnostics()));
        domains.add(runtimeBackedDomain(ConfidenceDomain.SPRING_BEANS, buildResolution.capabilities().spring(),
                runtimeEvidence, !mergedEvidence.beans().isEmpty(), "Spring bean evidence"));
        domains.add(runtimeBackedDomain(ConfidenceDomain.HTTP,
                buildResolution.capabilities().springWebMvc() || buildResolution.capabilities().springWebFlux(),
                runtimeEvidence, !mergedEvidence.endpoints().isEmpty(), "HTTP endpoint evidence"));
        domains.add(runtimeBackedDomain(ConfidenceDomain.JPA, buildResolution.capabilities().jpa(), runtimeEvidence,
                !mergedEvidence.jpaEntities().isEmpty(), "JPA entity evidence"));
        domains.add(runtimeBackedDomain(ConfidenceDomain.VALIDATION, buildResolution.capabilities().beanValidation(),
                runtimeEvidence, !mergedEvidence.validations().isEmpty(), "Validation constraint evidence"));
        domains.add(runtimeBackedDomain(ConfidenceDomain.SECURITY, buildResolution.capabilities().springSecurity(),
                runtimeEvidence, !mergedEvidence.securities().isEmpty(), "Security constraint evidence"));
        return ConfidenceReport.from(buildResolution.capabilities(), domains);
    }

    private ConfidenceReport.DomainConfidence runtimeBackedDomain(
            ConfidenceDomain domain,
            boolean requiredByCapabilities,
            RuntimeEvidence runtimeEvidence,
            boolean hasMergedFacts,
            String summary
    ) {
        if (!requiredByCapabilities) {
            return new ConfidenceReport.DomainConfidence(domain, DomainStatus.CONFIRMED,
                    summary + " not required for this project", List.of());
        }
        if (runtimeEvidence.bootSucceeded()) {
            return new ConfidenceReport.DomainConfidence(domain,
                    hasMergedFacts ? DomainStatus.CONFIRMED : DomainStatus.PARTIAL, summary,
                    runtimeEvidence.diagnostics());
        }
        return new ConfidenceReport.DomainConfidence(domain,
                hasMergedFacts ? DomainStatus.PARTIAL : DomainStatus.MISSING,
                summary + " requires runtime witness boot", runtimeEvidence.diagnostics());
    }

    private ConfidenceReport.DomainConfidence domain(
            ConfidenceDomain domain,
            DomainStatus status,
            String summary,
            List<String> details
    ) {
        return new ConfidenceReport.DomainConfidence(domain, status, summary, details);
    }
}
