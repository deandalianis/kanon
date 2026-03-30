package io.kanon.specctl.ir;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CanonicalIr(
        int schemaVersion,
        String specVersion,
        GeneratorLock generatorLock,
        Service service,
        Generation generation,
        Extraction extraction,
        Performance performance,
        Security security,
        Observability observability,
        Messaging messaging,
        DistributedModel distributedModel,
        List<BoundedContext> boundedContexts,
        EvidenceSidecar evidence
) {
    public CanonicalIr {
        boundedContexts = immutableList(boundedContexts);
        evidence = evidence == null ? new EvidenceSidecar(List.of(), List.of()) : evidence;
    }

    private static <T> List<T> immutableList(List<T> input) {
        return input == null ? List.of() : List.copyOf(input);
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> input) {
        return input == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(input));
    }

    public enum Delivery {
        AT_LEAST_ONCE
    }

    public enum Backoff {
        EXPONENTIAL
    }

    public enum RuleType {
        VALIDATION,
        GUARD,
        EFFECT
    }

    public enum Severity {
        ERROR,
        WARN
    }

    public record GeneratorLock(String specctl, Map<String, String> targets) {
        public GeneratorLock {
            targets = immutableMap(targets);
        }
    }

    public record Generation(boolean enabled, Targets targets) {
    }

    public record Targets(
            boolean springMvcControllers,
            boolean validation,
            boolean persistenceJpa,
            boolean flywayMigrations,
            boolean kafkaPublishers,
            boolean kafkaConsumers
    ) {
    }

    public record Extraction(boolean includeExternalDependencies) {
    }

    public record Performance(Pagination pagination, Batch batch, Cache cache) {
    }

    public record Pagination(int maxPageSize) {
    }

    public record Batch(int maxBatchSize) {
    }

    public record Cache(boolean enabled) {
    }

    public record Security(List<String> roles, List<Policy> policies) {
        public Security {
            roles = immutableList(roles);
            policies = immutableList(policies);
        }
    }

    public record Policy(String command, List<String> preAuthorize) {
        public Policy {
            preAuthorize = immutableList(preAuthorize);
        }
    }

    public record Observability(Metrics metrics) {
    }

    public record Metrics(
            Boolean commandTimers,
            Boolean ruleViolationCounter,
            Boolean kafkaPublishCounter,
            List<String> tags
    ) {
        public Metrics {
            tags = immutableList(tags);
        }
    }

    public record Messaging(Delivery delivery, Idempotency idempotency, Retry retry, String dlq) {
    }

    public record Idempotency(String key) {
    }

    public record Retry(int attempts, Backoff backoff) {
    }

    public record DistributedModel(List<ServiceTopology> services) {
        public DistributedModel {
            services = immutableList(services);
        }
    }

    public record ServiceTopology(String name, Ownership owns, List<Consumption> consumes) {
        public ServiceTopology {
            consumes = immutableList(consumes);
        }
    }

    public record Ownership(List<OwnedEvent> events) {
        public Ownership {
            events = immutableList(events);
        }
    }

    public record OwnedEvent(String name, String topic, EventSchema schema) {
    }

    public record Consumption(String event, String fromService, String topic, String compatibility) {
    }

    public record Identity(String type, String field) {
    }

    public record Http(String method, String path) {
    }

    public record EventSchema(String format, int version) {
    }

    public record Service(String name, String basePackage, String canonicalName, String canonicalPath,
                          String stableId) {
    }

    public record BoundedContext(
            String name,
            String canonicalName,
            String canonicalPath,
            String stableId,
            List<Aggregate> aggregates
    ) {
        public BoundedContext {
            aggregates = immutableList(aggregates);
        }
    }

    public record Aggregate(
            String name,
            String canonicalName,
            String canonicalPath,
            String stableId,
            Identity identity,
            List<Entity> entities,
            StateMachine stateMachine,
            List<Command> commands,
            List<Event> events,
            List<Hook> hooks
    ) {
        public Aggregate {
            entities = immutableList(entities);
            commands = immutableList(commands);
            events = immutableList(events);
            hooks = immutableList(hooks);
        }
    }

    public record Entity(
            String name,
            String canonicalName,
            String canonicalPath,
            String stableId,
            String table,
            List<Field> fields
    ) {
        public Entity {
            fields = immutableList(fields);
        }
    }

    public record Field(
            String name,
            String canonicalName,
            String canonicalPath,
            String stableId,
            String type,
            boolean pk,
            boolean nullable
    ) {
    }

    public record StateMachine(String name, String initial, List<String> states, List<Transition> transitions) {
        public StateMachine {
            states = immutableList(states);
            transitions = immutableList(transitions);
        }
    }

    public record Transition(String onCommandPath, List<String> fromStates, String toState) {
        public Transition {
            fromStates = immutableList(fromStates);
        }
    }

    public record Command(
            String name,
            String canonicalName,
            String canonicalPath,
            String stableId,
            Http http,
            List<Field> inputFields,
            List<Rule> rules,
            List<String> emittedEventPaths,
            List<BddScenario> scenarios
    ) {
        public Command {
            inputFields = immutableList(inputFields);
            rules = immutableList(rules);
            emittedEventPaths = immutableList(emittedEventPaths);
            scenarios = immutableList(scenarios);
        }
    }

    public record BddScenario(String name, List<BddStep> given, List<BddStep> when, List<BddStep> then) {
        public BddScenario {
            given = immutableList(given);
            when = immutableList(when);
            then = immutableList(then);
        }
    }

    public record BddStep(String step, ImplStep impl, String sourceHint) {
    }

    public record ImplStep(
            String type,
            String expr,
            String message,
            String target,
            String value,
            String service,
            String method,
            List<String> args,
            String event,
            String when,
            List<ImplStep> then,
            List<ImplStep> els
    ) {
        public ImplStep {
            args = immutableList(args);
            then = immutableList(then);
            els = immutableList(els);
        }
    }

    public record Rule(
            String id,
            String stableId,
            String canonicalPath,
            RuleType type,
            Severity severity,
            String source,
            RuleAst.Expression expression
    ) {
    }

    public record Event(
            String name,
            String canonicalName,
            String canonicalPath,
            String stableId,
            String topic,
            EventSchema schema,
            List<Field> payload
    ) {
        public Event {
            payload = immutableList(payload);
        }
    }

    public record Hook(String name, String signature, boolean optional) {
    }

    public record EvidenceSidecar(List<Evidence> evidence, List<Conflict> conflicts) {
        public EvidenceSidecar {
            evidence = immutableList(evidence);
            conflicts = immutableList(conflicts);
        }
    }

    public record Evidence(String nodePath, String provenance, double confidenceScore, Map<String, Object> metadata) {
        public Evidence {
            metadata = immutableMap(metadata);
        }
    }

    public record Conflict(String nodePath, String source, String message, boolean fatal) {
    }
}
