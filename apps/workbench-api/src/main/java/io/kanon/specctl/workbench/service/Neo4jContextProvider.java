package io.kanon.specctl.workbench.service;

import io.kanon.specctl.graph.neo4j.VersionedGraphService;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class Neo4jContextProvider {

    private final VersionedGraphService graphService = new VersionedGraphService();

    List<String> queryContext(String uri, String username, String password, String runId, String question) {
        List<String> keywords = tokenize(question);
        return graphService.queryContext(uri, username, password, runId, keywords);
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(t -> t.length() > 2)
                .distinct()
                .toList();
    }
}
