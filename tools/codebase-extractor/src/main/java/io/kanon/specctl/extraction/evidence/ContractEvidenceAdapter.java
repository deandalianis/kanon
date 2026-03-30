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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ContractEvidenceAdapter implements EvidenceAdapter {
    private static final Pattern PATH_ITEM = Pattern.compile("^\\s{2}(/[^:]+):\\s*$");
    private static final Pattern HTTP_METHOD = Pattern.compile("^\\s{4}(get|post|put|patch|delete|options|head):\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CHANNEL_ITEM = Pattern.compile("^\\s{2}([^\\s:#][^:]*):\\s*$");

    @Override
    public String name() {
        return "contracts";
    }

    @Override
    public EvidenceAdapterResult collect(EvidenceAdapterContext context) {
        List<EvidenceNode> nodes = new ArrayList<>();
        List<EvidenceEdge> edges = new ArrayList<>();
        List<EvidenceRef> refs = new ArrayList<>();
        for (Path file : context.resourceFiles()) {
            String lower = file.getFileName().toString().toLowerCase();
            boolean openapi = lower.contains("openapi") && (lower.endsWith(".yaml") || lower.endsWith(".yml"));
            boolean asyncapi = lower.contains("asyncapi") && (lower.endsWith(".yaml") || lower.endsWith(".yml"));
            if (!openapi && !asyncapi) {
                continue;
            }
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("format", openapi ? "openapi" : "asyncapi");
            EvidenceNode contract = new EvidenceNode(
                    EvidenceSupport.stableId("contract", file.toString()),
                    openapi ? "openapi-contract" : "asyncapi-contract",
                    file.getFileName().toString(),
                    file.toString(),
                    attributes
            );
            nodes.add(contract);
            refs.addAll(EvidenceSupport.fileBoundRefs(contract.id(), file));
            parseContract(file, contract.id(), openapi, nodes, edges, refs);
        }
        return new EvidenceAdapterResult(nodes, edges, refs, List.of());
    }

    private void parseContract(
            Path file,
            String contractId,
            boolean openapi,
            List<EvidenceNode> nodes,
            List<EvidenceEdge> edges,
            List<EvidenceRef> refs
    ) {
        try {
            List<String> lines = Files.readAllLines(file);
            String currentPath = null;
            boolean inChannels = false;
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (openapi) {
                    Matcher pathMatcher = PATH_ITEM.matcher(line);
                    if (pathMatcher.matches()) {
                        currentPath = pathMatcher.group(1).trim();
                        continue;
                    }
                    Matcher methodMatcher = HTTP_METHOD.matcher(line);
                    if (currentPath != null && methodMatcher.matches()) {
                        String method = methodMatcher.group(1).toUpperCase();
                        EvidenceNode op = new EvidenceNode(
                                EvidenceSupport.stableId("contract-op", file.toString(), currentPath, method),
                                "contract-operation",
                                method + " " + currentPath,
                                file.toString() + "#L" + (index + 1),
                                Map.of("method", method, "path", currentPath)
                        );
                        nodes.add(op);
                        edges.add(EvidenceSupport.edge("DECLARES", contractId, op.id()));
                        refs.add(new EvidenceRef(op.id(), contractId, file.toString(), index + 1, index + 1, line.trim()));
                    }
                } else {
                    if (line.trim().equals("channels:")) {
                        inChannels = true;
                        continue;
                    }
                    if (!inChannels) {
                        continue;
                    }
                    Matcher channelMatcher = CHANNEL_ITEM.matcher(line);
                    if (channelMatcher.matches()) {
                        String channel = channelMatcher.group(1).trim();
                        EvidenceNode op = new EvidenceNode(
                                EvidenceSupport.stableId("contract-channel", file.toString(), channel),
                                "contract-channel",
                                channel,
                                file.toString() + "#L" + (index + 1),
                                Map.of("channel", channel)
                        );
                        nodes.add(op);
                        edges.add(EvidenceSupport.edge("DECLARES", contractId, op.id()));
                        refs.add(new EvidenceRef(op.id(), contractId, file.toString(), index + 1, index + 1, line.trim()));
                    } else if (!line.startsWith(" ")) {
                        inChannels = false;
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }
}
