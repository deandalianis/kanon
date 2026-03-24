package io.kanon.specctl.dsl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SpecDocument(
        int schemaVersion,
        String specVersion,
        GeneratorLock generatorLock,
        Service service,
        Generation generation,
        Extraction extraction,
        Performance performance,
        List<BoundedContext> boundedContexts,
        Security security,
        Observability observability,
        Messaging messaging,
        DistributedModel distributedModel
) {
    public SpecDocument {
        boundedContexts = immutableList(boundedContexts);
    }

    public SpecDocument withBoundedContexts(List<BoundedContext> nextContexts) {
        return new SpecDocument(
                schemaVersion,
                specVersion,
                generatorLock,
                service,
                generation,
                extraction,
                performance,
                nextContexts,
                security,
                observability,
                messaging,
                distributedModel
        );
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GeneratorLock(String specctl, Map<String, String> targets) {
        public GeneratorLock {
            targets = immutableMap(targets);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Service(String name, String basePackage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Generation(boolean enabled, Targets targets) {
        public Generation {
            targets = targets == null ? new Targets(true, true, true, true, true, true) : targets;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Targets(
            boolean springMvcControllers,
            boolean validation,
            boolean persistenceJpa,
            boolean flywayMigrations,
            boolean kafkaPublishers,
            boolean kafkaConsumers
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Extraction(boolean includeExternalDependencies) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Performance(Pagination pagination, Batch batch, Cache cache) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pagination(int maxPageSize) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Batch(int maxBatchSize) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cache(boolean enabled) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BoundedContext(String name, List<Aggregate> aggregates) {
        public BoundedContext {
            aggregates = immutableList(aggregates);
        }

        public BoundedContext withAggregates(List<Aggregate> nextAggregates) {
            return new BoundedContext(name, nextAggregates);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Aggregate(
            String name,
            Identity id,
            List<Entity> entities,
            StateMachine stateMachine,
            List<Command> commands,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Event> events,
            List<Hook> hooks
    ) {
        public Aggregate {
            entities = immutableList(entities);
            commands = immutableList(commands);
            events = immutableList(events);
            hooks = immutableList(hooks);
        }

        public Aggregate withName(String nextName) {
            return new Aggregate(nextName, id, entities, stateMachine, commands, events, hooks);
        }

        public Aggregate withEntities(List<Entity> nextEntities) {
            return new Aggregate(name, id, nextEntities, stateMachine, commands, events, hooks);
        }

        public Aggregate withCommands(List<Command> nextCommands) {
            return new Aggregate(name, id, entities, stateMachine, nextCommands, events, hooks);
        }

        public Aggregate withEvents(List<Event> nextEvents) {
            return new Aggregate(name, id, entities, stateMachine, commands, nextEvents, hooks);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Identity(String type, String field) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entity(String name, String table, List<Field> fields) {
        public Entity {
            fields = immutableList(fields);
        }

        public Entity withFields(List<Field> nextFields) {
            return new Entity(name, table, nextFields);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Field(String name, String type, Boolean pk, Boolean nullable) {
        public boolean isPk() {
            return Boolean.TRUE.equals(pk);
        }

        public boolean isNullable() {
            return nullable == null || nullable;
        }

        public Field withName(String nextName) {
            return new Field(nextName, type, pk, nullable);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StateMachine(String name, List<String> states, String initial, List<Transition> transitions) {
        public StateMachine {
            states = immutableList(states);
            transitions = immutableList(transitions);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Transition(String on, List<String> from, String to) {
        public Transition {
            from = immutableList(from);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Command(String name, Http http, Input input, List<Rule> rules, @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> emits, List<BddScenario> scenarios) {
        public Command {
            rules = immutableList(rules);
            emits = immutableList(emits);
            scenarios = immutableList(scenarios);
        }

        public Command withName(String nextName) {
            return new Command(nextName, http, input, rules, emits, scenarios);
        }

        public Command withRules(List<Rule> nextRules) {
            return new Command(name, http, input, nextRules, emits, scenarios);
        }

        public Command withScenarios(List<BddScenario> nextScenarios) {
            return new Command(name, http, input, rules, emits, nextScenarios);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"name", "given", "when", "then"})
    public record BddScenario(String name, List<BddStep> given, List<BddStep> when, List<BddStep> then) {
        public BddScenario {
            given = immutableList(given);
            when = immutableList(when);
            then = immutableList(then);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BddStep(String step, ImplStep impl, String sourceHint) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Http(String method, String path) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Input(List<Field> fields) {
        public Input {
            fields = immutableList(fields);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Rule(String id, RuleType type, String ensure, Severity severity) {
        public Rule withEnsure(String nextEnsure) {
            return new Rule(id, type, nextEnsure, severity);
        }
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Event(String name, String topic, EventSchema schema, List<Field> payload) {
        public Event {
            payload = immutableList(payload);
        }

        public Event withName(String nextName) {
            return new Event(nextName, topic, schema, payload);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EventSchema(String format, int version) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hook(String name, String signature, Boolean optional) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Security(List<String> roles, List<Policy> policies) {
        public Security {
            roles = immutableList(roles);
            policies = immutableList(policies);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Policy(String command, List<String> preAuthorize) {
        public Policy {
            preAuthorize = immutableList(preAuthorize);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Observability(Metrics metrics) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Messaging(Delivery delivery, Idempotency idempotency, Retry retry, String dlq) {
    }

    public enum Delivery {
        AT_LEAST_ONCE
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Idempotency(String key) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Retry(int attempts, Backoff backoff) {
    }

    public enum Backoff {
        EXPONENTIAL
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DistributedModel(List<ServiceTopology> services) {
        public DistributedModel {
            services = immutableList(services);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServiceTopology(String name, Ownership owns, List<Consumption> consumes) {
        public ServiceTopology {
            consumes = immutableList(consumes);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ownership(List<OwnedEvent> events) {
        public Ownership {
            events = immutableList(events);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OwnedEvent(String name, String topic, EventSchema schema) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Consumption(String event, String fromService, String topic, String compatibility) {
    }

    private static <T> List<T> immutableList(List<T> input) {
        return input == null ? List.of() : List.copyOf(input);
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> input) {
        return input == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(input));
    }
}
