package io.kanon.specctl.graph.neo4j;

import io.kanon.specctl.extraction.evidence.EvidenceEdge;
import io.kanon.specctl.extraction.evidence.EvidenceNode;
import io.kanon.specctl.extraction.evidence.EvidenceRef;
import io.kanon.specctl.extraction.evidence.EvidenceSnapshot;
import io.kanon.specctl.semantic.SemanticSpecDocument;
import java.util.ArrayList;
import java.util.List;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;

public final class KnowledgeGraphService {
    public List<String> schemaStatements() {
        return List.of(
                "CREATE CONSTRAINT knowledge_run_id IF NOT EXISTS FOR (n:KnowledgeRun) REQUIRE n.run_id IS UNIQUE",
                "CREATE CONSTRAINT evidence_node_version IF NOT EXISTS FOR (n:EvidenceNode) REQUIRE (n.node_id, n.version) IS UNIQUE",
                "CREATE CONSTRAINT semantic_node_version IF NOT EXISTS FOR (n:SemanticNode) REQUIRE (n.node_id, n.version) IS UNIQUE",
                "CREATE CONSTRAINT evidence_ref_version IF NOT EXISTS FOR (n:EvidenceRef) REQUIRE (n.node_id, n.version) IS UNIQUE"
        );
    }

    public void ingest(
            String uri,
            String username,
            String password,
            String runId,
            EvidenceSnapshot evidenceSnapshot,
            SemanticSpecDocument semanticSpec
    ) {
        try (Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
             Session session = driver.session()) {
            for (String statement : ingestStatements(runId, evidenceSnapshot, semanticSpec)) {
                session.run(statement);
            }
        }
    }

    public List<String> ingestStatements(String runId, EvidenceSnapshot evidenceSnapshot, SemanticSpecDocument semanticSpec) {
        List<String> statements = new ArrayList<>(schemaStatements());
        statements.add("MERGE (r:KnowledgeRun {run_id: '" + escape(runId) + "'})");
        for (EvidenceNode node : evidenceSnapshot.nodes()) {
            statements.add("""
                    MERGE (n:EvidenceNode {node_id: '%s', version: '%s'})
                    SET n.kind = '%s', n.name = '%s', n.path = '%s'
                    """.formatted(
                    escape(node.id()),
                    escape(runId),
                    escape(node.kind()),
                    escape(node.label()),
                    escape(node.path())
            ).trim());
            statements.add("""
                    MATCH (r:KnowledgeRun {run_id: '%s'})
                    MATCH (n:EvidenceNode {node_id: '%s', version: '%s'})
                    MERGE (r)-[:HAS_EVIDENCE]->(n)
                    """.formatted(escape(runId), escape(node.id()), escape(runId)).trim());
        }
        for (EvidenceEdge edge : evidenceSnapshot.edges()) {
            statements.add("""
                    MATCH (a:EvidenceNode {node_id: '%s', version: '%s'})
                    MATCH (b:EvidenceNode {node_id: '%s', version: '%s'})
                    MERGE (a)-[:%s]->(b)
                    """.formatted(
                    escape(edge.sourceId()),
                    escape(runId),
                    escape(edge.targetId()),
                    escape(runId),
                    sanitizeRelationship(edge.kind())
            ).trim());
        }
        for (EvidenceRef ref : evidenceSnapshot.refs()) {
            String refId = ref.ownerId() + "@" + ref.file() + ":" + ref.startLine();
            statements.add("""
                    MERGE (n:EvidenceRef {node_id: '%s', version: '%s'})
                    SET n.file = '%s', n.startLine = %d, n.endLine = %d, n.excerpt = '%s'
                    """.formatted(
                    escape(refId),
                    escape(runId),
                    escape(ref.file()),
                    ref.startLine(),
                    ref.endLine(),
                    escape(ref.excerpt() == null ? "" : ref.excerpt())
            ).trim());
            statements.add("""
                    MATCH (a:EvidenceNode {node_id: '%s', version: '%s'})
                    MATCH (b:EvidenceRef {node_id: '%s', version: '%s'})
                    MERGE (a)-[:HAS_REF]->(b)
                    """.formatted(
                    escape(ref.ownerId()),
                    escape(runId),
                    escape(refId),
                    escape(runId)
            ).trim());
        }
        addSemanticStatements(statements, runId, semanticSpec);
        return statements;
    }

    public List<String> queryContext(String uri, String username, String password, String runId, List<String> keywords) {
        List<String> chunks = new ArrayList<>();
        try (Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
             Session session = driver.session()) {
            chunks.add(serviceSummary(session, runId));
            chunks.addAll(keywordMatches(session, runId, keywords));
        } catch (Exception ignored) {
        }
        chunks.removeIf(String::isBlank);
        return List.copyOf(chunks);
    }

    private void addSemanticStatements(List<String> statements, String runId, SemanticSpecDocument semanticSpec) {
        addSemanticNode(statements, runId, semanticSpec.service().id(), "service",
                semanticSpec.service().name(), semanticSpec.service().summary(), semanticSpec.service().basePackage());
        semanticSpec.service().evidence().forEach(citation -> deriveFromEvidence(statements, runId,
                semanticSpec.service().id(), citation.evidenceNodeId()));

        semanticSpec.interfaces().forEach(item -> {
            addSemanticNode(statements, runId, item.id(), "interface", item.name(), item.summary(), item.location());
            linkSemantic(statements, runId, semanticSpec.service().id(), item.id(), "DECLARES");
            item.evidence().forEach(citation -> deriveFromEvidence(statements, runId, item.id(), citation.evidenceNodeId()));
            item.operations().forEach(operation -> {
                addSemanticNode(statements, runId, operation.id(), "operation", operation.name(), operation.summary(),
                        operation.pathOrChannel());
                linkSemantic(statements, runId, item.id(), operation.id(), "DECLARES");
                operation.evidence().forEach(citation ->
                        deriveFromEvidence(statements, runId, operation.id(), citation.evidenceNodeId()));
            });
        });
        semanticSpec.dataStores().forEach(item -> {
            addSemanticNode(statements, runId, item.id(), "datastore", item.name(), item.summary(), item.technology());
            linkSemantic(statements, runId, semanticSpec.service().id(), item.id(), "USES");
            item.evidence().forEach(citation -> deriveFromEvidence(statements, runId, item.id(), citation.evidenceNodeId()));
        });
        semanticSpec.integrations().forEach(item -> {
            addSemanticNode(statements, runId, item.id(), "integration", item.name(), item.summary(), item.target());
            linkSemantic(statements, runId, semanticSpec.service().id(), item.id(), "INTEGRATES_WITH");
            item.evidence().forEach(citation -> deriveFromEvidence(statements, runId, item.id(), citation.evidenceNodeId()));
        });
        semanticSpec.workflows().forEach(item -> {
            addSemanticNode(statements, runId, item.id(), "workflow", item.name(), item.summary(), item.name());
            linkSemantic(statements, runId, semanticSpec.service().id(), item.id(), "RUNS");
            item.evidence().forEach(citation -> deriveFromEvidence(statements, runId, item.id(), citation.evidenceNodeId()));
        });
        semanticSpec.rules().forEach(item -> {
            addSemanticNode(statements, runId, item.id(), "rule", item.name(), item.statement(), item.category());
            linkSemantic(statements, runId, semanticSpec.service().id(), item.id(), "CONSTRAINS");
            item.evidence().forEach(citation -> deriveFromEvidence(statements, runId, item.id(), citation.evidenceNodeId()));
        });
        semanticSpec.scenarios().forEach(item -> {
            addSemanticNode(statements, runId, item.id(), "scenario", item.name(), String.join(" ", item.then()), item.name());
            linkSemantic(statements, runId, semanticSpec.service().id(), item.id(), "DESCRIBES");
            item.evidence().forEach(citation -> deriveFromEvidence(statements, runId, item.id(), citation.evidenceNodeId()));
        });
        semanticSpec.notes().forEach(item -> {
            addSemanticNode(statements, runId, item.id(), "note", item.title(), item.detail(), item.kind());
            item.evidence().forEach(citation -> deriveFromEvidence(statements, runId, item.id(), citation.evidenceNodeId()));
        });
    }

    private void addSemanticNode(
            List<String> statements,
            String runId,
            String nodeId,
            String kind,
            String name,
            String summary,
            String path
    ) {
        statements.add("""
                MERGE (n:SemanticNode {node_id: '%s', version: '%s'})
                SET n.kind = '%s', n.name = '%s', n.summary = '%s', n.path = '%s'
                """.formatted(
                escape(nodeId),
                escape(runId),
                escape(kind),
                escape(name),
                escape(summary == null ? "" : summary),
                escape(path == null ? "" : path)
        ).trim());
    }

    private void linkSemantic(List<String> statements, String runId, String sourceId, String targetId, String kind) {
        statements.add("""
                MATCH (a:SemanticNode {node_id: '%s', version: '%s'})
                MATCH (b:SemanticNode {node_id: '%s', version: '%s'})
                MERGE (a)-[:%s]->(b)
                """.formatted(
                escape(sourceId),
                escape(runId),
                escape(targetId),
                escape(runId),
                sanitizeRelationship(kind)
        ).trim());
    }

    private void deriveFromEvidence(List<String> statements, String runId, String semanticId, String evidenceId) {
        statements.add("""
                MATCH (a:SemanticNode {node_id: '%s', version: '%s'})
                MATCH (b:EvidenceNode {node_id: '%s', version: '%s'})
                MERGE (a)-[:DERIVED_FROM]->(b)
                """.formatted(
                escape(semanticId),
                escape(runId),
                escape(evidenceId),
                escape(runId)
        ).trim());
    }

    private String serviceSummary(Session session, String runId) {
        Result result = session.run(
                "MATCH (s:SemanticNode {version: $runId, kind: 'service'}) RETURN s.name AS name, s.summary AS summary",
                Values.parameters("runId", runId));
        if (!result.hasNext()) {
            return "";
        }
        Record record = result.next();
        return "=== SEMANTIC SERVICE ===\nName: " + record.get("name").asString() + "\nSummary: "
                + record.get("summary").asString("");
    }

    private List<String> keywordMatches(Session session, String runId, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return List.of();
        }
        Result result = session.run("""
                        MATCH (n)
                        WHERE n.version = $runId
                          AND (n:SemanticNode OR n:EvidenceNode)
                          AND any(keyword IN $keywords WHERE toLower(coalesce(n.name, '')) CONTAINS keyword
                            OR toLower(coalesce(n.summary, '')) CONTAINS keyword
                            OR toLower(coalesce(n.path, '')) CONTAINS keyword)
                        RETURN labels(n) AS labels, n.kind AS kind, n.name AS name, n.summary AS summary, n.path AS path
                        LIMIT 20
                        """,
                Values.parameters("runId", runId, "keywords", keywords));
        List<String> chunks = new ArrayList<>();
        while (result.hasNext()) {
            Record record = result.next();
            chunks.add("=== " + record.get("kind").asString("") + " ===\nName: "
                    + record.get("name").asString("") + "\nPath: " + record.get("path").asString("")
                    + "\nSummary: " + record.get("summary").asString(""));
        }
        return chunks;
    }

    private String sanitizeRelationship(String value) {
        return value.replaceAll("[^A-Z0-9_]", "_").toUpperCase();
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'");
    }
}
