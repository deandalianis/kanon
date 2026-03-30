package io.kanon.specctl.extraction.ir;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record ConfidenceReport(
        boolean trusted,
        Map<ConfidenceDomain, DomainConfidence> domains
) {
    public ConfidenceReport {
        domains = domains == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(domains));
    }

    public static ConfidenceReport from(ProjectCapabilities capabilities, List<DomainConfidence> values) {
        LinkedHashMap<ConfidenceDomain, DomainConfidence> domainMap = new LinkedHashMap<>();
        for (DomainConfidence value : values == null ? List.<DomainConfidence>of() : values) {
            domainMap.put(value.domain(), value);
        }
        Set<ConfidenceDomain> required = capabilities == null ? Set.of() : capabilities.requiredDomains();
        boolean trusted = required.stream().allMatch(domain -> {
            DomainConfidence confidence = domainMap.get(domain);
            return confidence != null && confidence.status() == DomainStatus.CONFIRMED;
        });
        return new ConfidenceReport(trusted, domainMap);
    }

    public DomainStatus statusFor(ConfidenceDomain domain) {
        DomainConfidence confidence = domains.get(domain);
        return confidence == null ? DomainStatus.MISSING : confidence.status();
    }

    public record DomainConfidence(
            ConfidenceDomain domain,
            DomainStatus status,
            String summary,
            List<String> details
    ) {
        public DomainConfidence {
            status = status == null ? DomainStatus.MISSING : status;
            details = details == null ? List.of() : List.copyOf(details);
        }
    }
}
