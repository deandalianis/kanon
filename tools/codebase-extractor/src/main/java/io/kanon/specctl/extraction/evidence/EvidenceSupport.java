package io.kanon.specctl.extraction.evidence;

import io.kanon.specctl.extraction.evidence.EvidenceEdge;
import io.kanon.specctl.extraction.evidence.EvidenceNode;
import io.kanon.specctl.extraction.evidence.EvidenceRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class EvidenceSupport {
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");

    private EvidenceSupport() {
    }

    static String stableId(String prefix, String... parts) {
        StringBuilder builder = new StringBuilder(prefix);
        for (String part : parts) {
            builder.append(':').append(part == null ? "" : part);
        }
        return builder.toString();
    }

    static String fileNodeId(Path path) {
        return stableId("file", path.toAbsolutePath().normalize().toString());
    }

    static String excerpt(Path file, int startLine, int endLine) {
        try {
            List<String> lines = Files.readAllLines(file);
            int start = Math.max(1, startLine);
            int end = Math.max(start, endLine);
            StringBuilder builder = new StringBuilder();
            for (int index = start - 1; index < Math.min(lines.size(), end); index++) {
                builder.append(lines.get(index)).append(System.lineSeparator());
            }
            return builder.toString().trim();
        } catch (IOException ignored) {
            return "";
        }
    }

    static List<EvidenceNode> markdownSectionNodes(Path file) {
        List<EvidenceNode> sections = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(file);
            for (int index = 0; index < lines.size(); index++) {
                Matcher matcher = HEADING.matcher(lines.get(index));
                if (!matcher.matches()) {
                    continue;
                }
                String title = matcher.group(2).trim();
                Map<String, String> attributes = new LinkedHashMap<>();
                attributes.put("level", String.valueOf(matcher.group(1).length()));
                attributes.put("file", file.toString());
                attributes.put("line", String.valueOf(index + 1));
                sections.add(new EvidenceNode(
                        stableId("doc-section", file.toString(), String.valueOf(index + 1), title),
                        "documentation-section",
                        title,
                        file.toString() + "#L" + (index + 1),
                        attributes
                ));
            }
        } catch (IOException ignored) {
        }
        return sections;
    }

    static List<String> importStatements(Path file) {
        try {
            return Files.readAllLines(file).stream()
                    .map(String::trim)
                    .filter(line -> line.startsWith("import "))
                    .map(line -> line.substring("import ".length()).replace(";", "").trim())
                    .toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    static boolean hasAnySegment(Path file, String... segments) {
        String normalized = file.toString().toLowerCase(Locale.ROOT);
        for (String segment : segments) {
            if (normalized.contains(segment.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static List<EvidenceRef> fileBoundRefs(String ownerId, Path file) {
        return List.of(new EvidenceRef(ownerId, fileNodeId(file), file.toString(), 1, 1, ""));
    }

    static EvidenceEdge edge(String kind, String sourceId, String targetId) {
        return new EvidenceEdge(stableId("edge", kind, sourceId, targetId), sourceId, targetId, kind);
    }
}
