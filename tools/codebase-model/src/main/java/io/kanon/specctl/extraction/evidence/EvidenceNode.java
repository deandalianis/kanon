package io.kanon.specctl.extraction.evidence;

import java.util.LinkedHashMap;
import java.util.Map;

public record EvidenceNode(
        String id,
        String kind,
        String label,
        String path,
        Map<String, String> attributes
) {
    public EvidenceNode {
        attributes = attributes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(attributes));
    }
}
