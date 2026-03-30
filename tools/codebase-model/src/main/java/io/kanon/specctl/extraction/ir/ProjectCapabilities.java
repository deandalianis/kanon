package io.kanon.specctl.extraction.ir;

import java.util.LinkedHashSet;
import java.util.Set;

public record ProjectCapabilities(
        boolean plainJava,
        boolean spring,
        boolean springBoot,
        boolean springWebMvc,
        boolean springWebFlux,
        boolean jpa,
        boolean beanValidation,
        boolean springSecurity
) {
    public static ProjectCapabilities minimal() {
        return new ProjectCapabilities(true, false, false, false, false, false, false, false);
    }

    public Set<ConfidenceDomain> requiredDomains() {
        LinkedHashSet<ConfidenceDomain> domains = new LinkedHashSet<>();
        domains.add(ConfidenceDomain.BUILD);
        domains.add(ConfidenceDomain.TYPES);
        domains.add(ConfidenceDomain.METHOD_BODIES);
        domains.add(ConfidenceDomain.CALL_GRAPH);
        if (spring) {
            domains.add(ConfidenceDomain.SPRING_BEANS);
        }
        if (springWebMvc || springWebFlux) {
            domains.add(ConfidenceDomain.HTTP);
        }
        if (jpa) {
            domains.add(ConfidenceDomain.JPA);
        }
        if (beanValidation) {
            domains.add(ConfidenceDomain.VALIDATION);
        }
        if (springSecurity) {
            domains.add(ConfidenceDomain.SECURITY);
        }
        return Set.copyOf(domains);
    }
}
