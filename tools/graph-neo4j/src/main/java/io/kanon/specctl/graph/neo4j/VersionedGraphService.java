package io.kanon.specctl.graph.neo4j;

import io.kanon.specctl.core.extract.ExtractionResult;
import io.kanon.specctl.ir.CanonicalIr;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;

import java.util.ArrayList;
import java.util.List;

public final class VersionedGraphService {
    public List<String> schemaStatements() {
        return List.of(
                "CREATE CONSTRAINT service_node_version IF NOT EXISTS FOR (n:Service) REQUIRE (n.node_id, n.version) IS UNIQUE",
                "CREATE CONSTRAINT bounded_context_node_version IF NOT EXISTS FOR (n:BoundedContext) REQUIRE (n.node_id, n.version) IS UNIQUE",
                "CREATE CONSTRAINT aggregate_node_version IF NOT EXISTS FOR (n:Aggregate) REQUIRE (n.node_id, n.version) IS UNIQUE",
                "CREATE CONSTRAINT entity_node_version IF NOT EXISTS FOR (n:Entity) REQUIRE (n.node_id, n.version) IS UNIQUE",
                "CREATE CONSTRAINT field_node_version IF NOT EXISTS FOR (n:Field) REQUIRE (n.node_id, n.version) IS UNIQUE",
                "CREATE CONSTRAINT command_node_version IF NOT EXISTS FOR (n:Command) REQUIRE (n.node_id, n.version) IS UNIQUE",
                "CREATE CONSTRAINT event_node_version IF NOT EXISTS FOR (n:Event) REQUIRE (n.node_id, n.version) IS UNIQUE",
                "CREATE CONSTRAINT rule_node_version IF NOT EXISTS FOR (n:Rule) REQUIRE (n.node_id, n.version) IS UNIQUE",
                "CREATE CONSTRAINT code_anchor_node_version IF NOT EXISTS FOR (n:CodeAnchor) REQUIRE (n.node_id, n.version) IS UNIQUE",
                "CREATE CONSTRAINT generator_run_id IF NOT EXISTS FOR (n:GeneratorRun) REQUIRE n.run_id IS UNIQUE"
        );
    }

    public List<String> ingestStatements(String generatorRunId, CanonicalIr ir, ExtractionResult extractionResult) {
        List<String> statements = new ArrayList<>(schemaStatements());
        statements.add("MERGE (r:GeneratorRun {run_id: '" + escape(generatorRunId) + "'})");
        statements.add(node("Service", ir.service().stableId(), generatorRunId, ir.service().name(), ir.service().canonicalPath()));
        for (CanonicalIr.BoundedContext boundedContext : ir.boundedContexts()) {
            statements.add(node("BoundedContext", boundedContext.stableId(), generatorRunId, boundedContext.name(), boundedContext.canonicalPath()));
            statements.add(rel("Service", ir.service().stableId(), "DECLARES", "BoundedContext", boundedContext.stableId(), generatorRunId));
            for (CanonicalIr.Aggregate aggregate : boundedContext.aggregates()) {
                statements.add(node("Aggregate", aggregate.stableId(), generatorRunId, aggregate.name(), aggregate.canonicalPath()));
                statements.add(rel("BoundedContext", boundedContext.stableId(), "DECLARES", "Aggregate", aggregate.stableId(), generatorRunId));
                for (CanonicalIr.Entity entity : aggregate.entities()) {
                    statements.add(node("Entity", entity.stableId(), generatorRunId, entity.name(), entity.canonicalPath()));
                    statements.add(rel("Aggregate", aggregate.stableId(), "DECLARES", "Entity", entity.stableId(), generatorRunId));
                    for (CanonicalIr.Field field : entity.fields()) {
                        statements.add(node("Field", field.stableId(), generatorRunId, field.name(), field.canonicalPath()));
                        statements.add(rel("Entity", entity.stableId(), "HAS_FIELD", "Field", field.stableId(), generatorRunId));
                    }
                }
                for (CanonicalIr.Event event : aggregate.events()) {
                    statements.add(node("Event", event.stableId(), generatorRunId, event.name(), event.canonicalPath()));
                    statements.add(rel("Aggregate", aggregate.stableId(), "DECLARES", "Event", event.stableId(), generatorRunId));
                }
                for (CanonicalIr.Command command : aggregate.commands()) {
                    statements.add(node("Command", command.stableId(), generatorRunId, command.name(), command.canonicalPath()));
                    statements.add(rel("Aggregate", aggregate.stableId(), "DECLARES", "Command", command.stableId(), generatorRunId));
                    for (CanonicalIr.Rule rule : command.rules()) {
                        statements.add(node("Rule", rule.stableId(), generatorRunId, rule.id(), rule.canonicalPath()));
                        statements.add(rel("Command", command.stableId(), "GUARDED_BY", "Rule", rule.stableId(), generatorRunId));
                    }
                }
            }
        }
        for (ExtractionResult.Provenance anchor : extractionResult.provenance()) {
            String nodeId = anchor.path() + "#" + anchor.file() + ":" + anchor.startLine();
            statements.add(
                    "MERGE (a:CodeAnchor {node_id: '" + escape(nodeId) + "', version: '" + escape(generatorRunId) + "'}) "
                            + "SET a.path = '" + escape(anchor.path()) + "', a.file = '" + escape(anchor.file()) + "', a.symbol = '"
                            + escape(anchor.symbol()) + "', a.startLine = " + anchor.startLine() + ", a.endLine = " + anchor.endLine()
            );
        }
        return statements;
    }

    public void ingest(String uri, String username, String password, String generatorRunId, CanonicalIr ir, ExtractionResult extractionResult) {
        try (Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
             org.neo4j.driver.Session session = driver.session()) {
            for (String statement : ingestStatements(generatorRunId, ir, extractionResult)) {
                session.run(statement);
            }
        }
    }

    public String diffQuery(String fromVersion, String toVersion) {
        return """
                MATCH (a)
                WHERE a.version = '%s'
                OPTIONAL MATCH (b {node_id: a.node_id, version: '%s'})
                WHERE b IS NULL
                RETURN a.node_id AS nodeId, labels(a) AS labels, 'REMOVED' AS change
                UNION
                MATCH (b)
                WHERE b.version = '%s'
                OPTIONAL MATCH (a {node_id: b.node_id, version: '%s'})
                WHERE a IS NULL
                RETURN b.node_id AS nodeId, labels(b) AS labels, 'ADDED' AS change
                """.formatted(escape(fromVersion), escape(toVersion), escape(toVersion), escape(fromVersion));
    }

    private String node(String label, String nodeId, String version, String name, String path) {
        return "MERGE (n:" + label + " {node_id: '" + escape(nodeId) + "', version: '" + escape(version) + "'}) "
                + "SET n.name = '" + escape(name) + "', n.path = '" + escape(path) + "'";
    }

    private String rel(String leftLabel, String leftId, String type, String rightLabel, String rightId, String version) {
        return "MATCH (a:" + leftLabel + " {node_id: '" + escape(leftId) + "', version: '" + escape(version) + "'}) "
                + "MATCH (b:" + rightLabel + " {node_id: '" + escape(rightId) + "', version: '" + escape(version) + "'}) "
                + "MERGE (a)-[:" + type + "]->(b)";
    }

    private String escape(String value) {
        return value.replace("'", "\\'");
    }
}
