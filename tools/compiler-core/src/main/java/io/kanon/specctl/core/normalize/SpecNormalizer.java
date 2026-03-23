package io.kanon.specctl.core.normalize;

import io.kanon.specctl.core.diagnostics.Diagnostics;
import io.kanon.specctl.core.rule.RuleAnalyzer;
import io.kanon.specctl.core.rule.RuleParser;
import io.kanon.specctl.dsl.SpecDocument;
import io.kanon.specctl.ir.CanonicalIr;
import io.kanon.specctl.ir.RuleAst;
import io.kanon.specctl.ir.StableIds;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SpecNormalizer {
    private final RuleParser ruleParser = new RuleParser();
    private final RuleAnalyzer ruleAnalyzer = new RuleAnalyzer();

    public CanonicalIr normalize(SpecDocument input, Diagnostics diagnostics) {
        SpecDocument spec = applyDefaults(input);
        validateTopLevel(spec, diagnostics);

        String serviceCanonicalName = CanonicalNames.canonicalToken(spec.service().name());
        String servicePath = CanonicalPaths.servicePath(spec.service().name());
        String serviceId = StableIds.stableId(
                "service",
                serviceCanonicalName,
                "/",
                Map.of("basePackage", spec.service().basePackage())
        );

        List<CanonicalIr.BoundedContext> contexts = spec.boundedContexts().stream()
                .sorted(Comparator.comparing(context -> CanonicalNames.canonicalToken(context.name())))
                .map(context -> normalizeContext(servicePath, context, diagnostics))
                .toList();

        diagnostics.throwIfErrors();
        return new CanonicalIr(
                spec.schemaVersion(),
                spec.specVersion(),
                mapGeneratorLock(spec.generatorLock()),
                new CanonicalIr.Service(spec.service().name(), spec.service().basePackage(), serviceCanonicalName, servicePath, serviceId),
                mapGeneration(spec.generation()),
                mapExtraction(spec.extraction()),
                mapPerformance(spec.performance()),
                mapSecurity(spec.security()),
                mapObservability(spec.observability()),
                mapMessaging(spec.messaging()),
                mapDistributedModel(spec.distributedModel()),
                contexts,
                new CanonicalIr.EvidenceSidecar(List.of(), List.of())
        );
    }

    private SpecDocument applyDefaults(SpecDocument input) {
        SpecDocument.Generation generation = input.generation() == null
                ? new SpecDocument.Generation(true, new SpecDocument.Targets(true, true, true, true, true, true))
                : input.generation();
        SpecDocument.Extraction extraction = input.extraction() == null
                ? new SpecDocument.Extraction(false)
                : input.extraction();
        SpecDocument.Performance performance = input.performance() == null
                ? new SpecDocument.Performance(
                new SpecDocument.Pagination(100),
                new SpecDocument.Batch(500),
                new SpecDocument.Cache(false)
        )
                : new SpecDocument.Performance(
                input.performance().pagination() == null ? new SpecDocument.Pagination(100) : input.performance().pagination(),
                input.performance().batch() == null ? new SpecDocument.Batch(500) : input.performance().batch(),
                input.performance().cache() == null ? new SpecDocument.Cache(false) : input.performance().cache()
        );
        SpecDocument.Observability observability = input.observability() == null
                ? new SpecDocument.Observability(new SpecDocument.Metrics(true, true, true, List.of("service", "boundedContext", "aggregate", "command")))
                : input.observability();
        SpecDocument.Messaging messaging = input.messaging() == null
                ? new SpecDocument.Messaging(
                SpecDocument.Delivery.AT_LEAST_ONCE,
                new SpecDocument.Idempotency("requestId"),
                new SpecDocument.Retry(3, SpecDocument.Backoff.EXPONENTIAL),
                "default.dlq"
        )
                : new SpecDocument.Messaging(
                input.messaging().delivery() == null ? SpecDocument.Delivery.AT_LEAST_ONCE : input.messaging().delivery(),
                input.messaging().idempotency(),
                input.messaging().retry() == null ? new SpecDocument.Retry(3, SpecDocument.Backoff.EXPONENTIAL) : input.messaging().retry(),
                input.messaging().dlq()
        );
        return new SpecDocument(
                input.schemaVersion(),
                input.specVersion(),
                input.generatorLock(),
                input.service(),
                generation,
                extraction,
                performance,
                input.boundedContexts(),
                input.security(),
                observability,
                messaging,
                input.distributedModel()
        );
    }

    private void validateTopLevel(SpecDocument spec, Diagnostics diagnostics) {
        if (spec.schemaVersion() != 1) {
            diagnostics.error("SCHEMA_VERSION_UNSUPPORTED", "Only schemaVersion=1 is currently supported", "/schemaVersion");
        }
        if (spec.service() == null || spec.service().name() == null || spec.service().name().isBlank()) {
            diagnostics.error("SERVICE_NAME_REQUIRED", "service.name is required", "/service");
        }
        if (spec.generatorLock() == null || spec.generatorLock().specctl() == null || spec.generatorLock().specctl().isBlank()) {
            diagnostics.error("GENERATOR_LOCK_REQUIRED", "generatorLock.specctl is required", "/generatorLock");
        }
        if (spec.messaging() != null && (spec.messaging().idempotency() == null || spec.messaging().idempotency().key() == null || spec.messaging().idempotency().key().isBlank())) {
            diagnostics.error("MESSAGING_IDEMPOTENCY_REQUIRED", "messaging.idempotency.key is required", "/messaging/idempotency");
        }
    }

    private CanonicalIr.BoundedContext normalizeContext(
            String servicePath,
            SpecDocument.BoundedContext context,
            Diagnostics diagnostics
    ) {
        String canonicalName = CanonicalNames.canonicalToken(context.name());
        String contextPath = CanonicalPaths.boundedContextPath(servicePath, context.name());
        List<CanonicalIr.Aggregate> aggregates = context.aggregates().stream()
                .sorted(Comparator.comparing(aggregate -> CanonicalNames.canonicalToken(aggregate.name())))
                .map(aggregate -> normalizeAggregate(contextPath, aggregate, diagnostics))
                .toList();
        String stableId = StableIds.stableId(
                "boundedContext",
                canonicalName,
                servicePath,
                Map.of("aggregateNames", aggregates.stream().map(CanonicalIr.Aggregate::canonicalName).toList())
        );
        return new CanonicalIr.BoundedContext(context.name(), canonicalName, contextPath, stableId, aggregates);
    }

    private CanonicalIr.Aggregate normalizeAggregate(
            String contextPath,
            SpecDocument.Aggregate aggregate,
            Diagnostics diagnostics
    ) {
        String canonicalName = CanonicalNames.canonicalToken(aggregate.name());
        String aggregatePath = CanonicalPaths.aggregatePath(contextPath, aggregate.name());

        List<CanonicalIr.Entity> entities = aggregate.entities().stream()
                .sorted(Comparator.comparing(entity -> CanonicalNames.canonicalToken(entity.name())))
                .map(entity -> normalizeEntity(aggregatePath, entity))
                .toList();

        List<SpecDocument.Event> sortedEvents = aggregate.events().stream()
                .sorted(Comparator.comparing(event -> CanonicalNames.canonicalToken(event.name())))
                .toList();
        Map<String, String> eventPaths = new HashMap<>();
        List<CanonicalIr.Event> events = sortedEvents.stream()
                .map(event -> normalizeEvent(aggregatePath, event, eventPaths))
                .toList();

        List<CanonicalIr.Command> commands = aggregate.commands().stream()
                .sorted(Comparator.comparing(command -> CanonicalNames.canonicalToken(command.name())))
                .map(command -> normalizeCommand(aggregate, aggregatePath, command, eventPaths, diagnostics))
                .toList();

        CanonicalIr.StateMachine stateMachine = null;
        if (aggregate.stateMachine() != null) {
            stateMachine = new CanonicalIr.StateMachine(
                    aggregate.stateMachine().name(),
                    aggregate.stateMachine().initial(),
                    aggregate.stateMachine().states().stream().sorted().toList(),
                    aggregate.stateMachine().transitions().stream()
                            .sorted(Comparator.comparing(SpecDocument.Transition::on))
                            .map(transition -> new CanonicalIr.Transition(
                                    CanonicalPaths.commandPath(aggregatePath, transition.on()),
                                    transition.from().stream().sorted().toList(),
                                    transition.to()
                            ))
                            .toList()
            );
        }

        List<CanonicalIr.Hook> hooks = aggregate.hooks().stream()
                .sorted(Comparator.comparing(hook -> CanonicalNames.canonicalToken(hook.name())))
                .map(hook -> new CanonicalIr.Hook(hook.name(), hook.signature(), Boolean.TRUE.equals(hook.optional())))
                .toList();

        String stableId = StableIds.stableId(
                "aggregate",
                canonicalName,
                contextPath,
                Map.of(
                        "identityField", aggregate.id() == null ? null : aggregate.id().field(),
                        "entityNames", entities.stream().map(CanonicalIr.Entity::canonicalName).toList(),
                        "commandNames", commands.stream().map(CanonicalIr.Command::canonicalName).toList(),
                        "eventNames", events.stream().map(CanonicalIr.Event::canonicalName).toList()
                )
        );
        return new CanonicalIr.Aggregate(
                aggregate.name(),
                canonicalName,
                aggregatePath,
                stableId,
                mapIdentity(aggregate.id()),
                entities,
                stateMachine,
                commands,
                events,
                hooks
        );
    }

    private CanonicalIr.Entity normalizeEntity(String aggregatePath, SpecDocument.Entity entity) {
        String canonicalName = CanonicalNames.canonicalToken(entity.name());
        String entityPath = CanonicalPaths.entityPath(aggregatePath, entity.name());
        List<CanonicalIr.Field> fields = entity.fields().stream()
                .sorted(Comparator.comparing(field -> CanonicalNames.canonicalToken(field.name())))
                .map(field -> normalizeField(entityPath, field))
                .toList();
        String stableId = StableIds.stableId(
                "entity",
                canonicalName,
                aggregatePath,
                Map.of("table", entity.table(), "fieldNames", fields.stream().map(CanonicalIr.Field::canonicalName).toList())
        );
        return new CanonicalIr.Entity(entity.name(), canonicalName, entityPath, stableId, entity.table(), fields);
    }

    private CanonicalIr.Field normalizeField(String parentPath, SpecDocument.Field field) {
        String canonicalName = CanonicalNames.canonicalToken(field.name());
        String fieldPath = CanonicalPaths.fieldPath(parentPath, field.name());
        String stableId = StableIds.stableId(
                "field",
                canonicalName,
                parentPath,
                Map.of("type", field.type(), "pk", field.isPk(), "nullable", field.isNullable())
        );
        return new CanonicalIr.Field(field.name(), canonicalName, fieldPath, stableId, field.type(), field.isPk(), field.isNullable());
    }

    private CanonicalIr.Event normalizeEvent(String aggregatePath, SpecDocument.Event event, Map<String, String> eventPaths) {
        String canonicalName = CanonicalNames.canonicalToken(event.name());
        String eventPath = CanonicalPaths.eventPath(aggregatePath, event.name());
        List<CanonicalIr.Field> payload = event.payload().stream()
                .sorted(Comparator.comparing(field -> CanonicalNames.canonicalToken(field.name())))
                .map(field -> normalizeField(eventPath, field))
                .toList();
        String stableId = StableIds.stableId(
                "event",
                canonicalName,
                aggregatePath,
                Map.of("topic", event.topic(), "schemaFormat", event.schema().format(), "payload", payload.stream().map(CanonicalIr.Field::canonicalName).toList())
        );
        eventPaths.put(event.name(), eventPath);
        eventPaths.put(canonicalName, eventPath);
        return new CanonicalIr.Event(event.name(), canonicalName, eventPath, stableId, event.topic(), mapEventSchema(event.schema()), payload);
    }

    private CanonicalIr.Command normalizeCommand(
            SpecDocument.Aggregate aggregate,
            String aggregatePath,
            SpecDocument.Command command,
            Map<String, String> eventPaths,
            Diagnostics diagnostics
    ) {
        String canonicalName = CanonicalNames.canonicalToken(command.name());
        String commandPath = CanonicalPaths.commandPath(aggregatePath, command.name());
        List<CanonicalIr.Field> inputFields = command.input() == null
                ? List.of()
                : command.input().fields().stream()
                .sorted(Comparator.comparing(field -> CanonicalNames.canonicalToken(field.name())))
                .map(field -> normalizeField(commandPath + "/input", field))
                .toList();

        List<RuleAst.ParsedRule> parsedRules = command.rules().stream()
                .sorted(Comparator.comparing(rule -> CanonicalNames.canonicalToken(rule.id())))
                .map(ruleParser::parse)
                .toList();
        ruleAnalyzer.detectConflicts(commandPath, parsedRules, ruleAnalyzer.buildSymbolTable(aggregate, command), diagnostics);

        List<CanonicalIr.Rule> rules = parsedRules.stream()
                .map(rule -> new CanonicalIr.Rule(
                        rule.id(),
                        StableIds.stableId(
                                "rule",
                                CanonicalNames.canonicalToken(rule.id()),
                                commandPath,
                                Map.of("type", rule.type(), "source", rule.source())
                        ),
                        CanonicalPaths.rulePath(commandPath, rule.id()),
                        rule.type(),
                        rule.severity(),
                        rule.source(),
                        rule.expression()
                ))
                .toList();

        List<String> emittedEventPaths = command.emits().stream()
                .map(eventName -> resolveEventPath(commandPath, eventName, eventPaths, diagnostics))
                .filter(Objects::nonNull)
                .sorted()
                .toList();

        String stableId = StableIds.stableId(
                "command",
                canonicalName,
                aggregatePath,
                Map.of(
                        "method", command.http().method(),
                        "path", command.http().path(),
                        "rules", rules.stream().map(CanonicalIr.Rule::id).toList(),
                        "emits", emittedEventPaths
                )
        );
        return new CanonicalIr.Command(command.name(), canonicalName, commandPath, stableId, mapHttp(command.http()), inputFields, rules, emittedEventPaths);
    }

    private String resolveEventPath(String commandPath, String eventName, Map<String, String> eventPaths, Diagnostics diagnostics) {
        String candidate = eventPaths.get(eventName);
        if (candidate == null) {
            candidate = eventPaths.get(CanonicalNames.canonicalToken(eventName));
        }
        if (candidate == null) {
            diagnostics.error("EVENT_REFERENCE_UNKNOWN", "Command emits unknown event '" + eventName + "'", commandPath);
        }
        return candidate;
    }

    private CanonicalIr.GeneratorLock mapGeneratorLock(SpecDocument.GeneratorLock input) {
        if (input == null) {
            return null;
        }
        return new CanonicalIr.GeneratorLock(input.specctl(), input.targets());
    }

    private CanonicalIr.Generation mapGeneration(SpecDocument.Generation input) {
        if (input == null) {
            return null;
        }
        return new CanonicalIr.Generation(input.enabled(), mapTargets(input.targets()));
    }

    private CanonicalIr.Targets mapTargets(SpecDocument.Targets input) {
        if (input == null) {
            return null;
        }
        return new CanonicalIr.Targets(
                input.springMvcControllers(),
                input.validation(),
                input.persistenceJpa(),
                input.flywayMigrations(),
                input.kafkaPublishers(),
                input.kafkaConsumers()
        );
    }

    private CanonicalIr.Extraction mapExtraction(SpecDocument.Extraction input) {
        if (input == null) {
            return null;
        }
        return new CanonicalIr.Extraction(input.includeExternalDependencies());
    }

    private CanonicalIr.Performance mapPerformance(SpecDocument.Performance input) {
        if (input == null) {
            return null;
        }
        return new CanonicalIr.Performance(
                mapPagination(input.pagination()),
                mapBatch(input.batch()),
                mapCache(input.cache())
        );
    }

    private CanonicalIr.Pagination mapPagination(SpecDocument.Pagination input) {
        return input == null ? null : new CanonicalIr.Pagination(input.maxPageSize());
    }

    private CanonicalIr.Batch mapBatch(SpecDocument.Batch input) {
        return input == null ? null : new CanonicalIr.Batch(input.maxBatchSize());
    }

    private CanonicalIr.Cache mapCache(SpecDocument.Cache input) {
        return input == null ? null : new CanonicalIr.Cache(input.enabled());
    }

    private CanonicalIr.Security mapSecurity(SpecDocument.Security input) {
        if (input == null) {
            return null;
        }
        return new CanonicalIr.Security(
                input.roles(),
                input.policies().stream().map(this::mapPolicy).toList()
        );
    }

    private CanonicalIr.Policy mapPolicy(SpecDocument.Policy input) {
        return new CanonicalIr.Policy(input.command(), input.preAuthorize());
    }

    private CanonicalIr.Observability mapObservability(SpecDocument.Observability input) {
        if (input == null) {
            return null;
        }
        return new CanonicalIr.Observability(mapMetrics(input.metrics()));
    }

    private CanonicalIr.Metrics mapMetrics(SpecDocument.Metrics input) {
        if (input == null) {
            return null;
        }
        return new CanonicalIr.Metrics(
                input.commandTimers(),
                input.ruleViolationCounter(),
                input.kafkaPublishCounter(),
                input.tags()
        );
    }

    private CanonicalIr.Messaging mapMessaging(SpecDocument.Messaging input) {
        if (input == null) {
            return null;
        }
        return new CanonicalIr.Messaging(
                input.delivery() == null ? null : CanonicalIr.Delivery.valueOf(input.delivery().name()),
                mapIdempotency(input.idempotency()),
                mapRetry(input.retry()),
                input.dlq()
        );
    }

    private CanonicalIr.Idempotency mapIdempotency(SpecDocument.Idempotency input) {
        return input == null ? null : new CanonicalIr.Idempotency(input.key());
    }

    private CanonicalIr.Retry mapRetry(SpecDocument.Retry input) {
        if (input == null) {
            return null;
        }
        return new CanonicalIr.Retry(
                input.attempts(),
                input.backoff() == null ? null : CanonicalIr.Backoff.valueOf(input.backoff().name())
        );
    }

    private CanonicalIr.DistributedModel mapDistributedModel(SpecDocument.DistributedModel input) {
        if (input == null) {
            return null;
        }
        return new CanonicalIr.DistributedModel(input.services().stream().map(this::mapServiceTopology).toList());
    }

    private CanonicalIr.ServiceTopology mapServiceTopology(SpecDocument.ServiceTopology input) {
        return new CanonicalIr.ServiceTopology(
                input.name(),
                mapOwnership(input.owns()),
                input.consumes().stream().map(this::mapConsumption).toList()
        );
    }

    private CanonicalIr.Ownership mapOwnership(SpecDocument.Ownership input) {
        return input == null ? null : new CanonicalIr.Ownership(input.events().stream().map(this::mapOwnedEvent).toList());
    }

    private CanonicalIr.OwnedEvent mapOwnedEvent(SpecDocument.OwnedEvent input) {
        return new CanonicalIr.OwnedEvent(input.name(), input.topic(), mapEventSchema(input.schema()));
    }

    private CanonicalIr.Consumption mapConsumption(SpecDocument.Consumption input) {
        return new CanonicalIr.Consumption(input.event(), input.fromService(), input.topic(), input.compatibility());
    }

    private CanonicalIr.Identity mapIdentity(SpecDocument.Identity input) {
        return input == null ? null : new CanonicalIr.Identity(input.type(), input.field());
    }

    private CanonicalIr.Http mapHttp(SpecDocument.Http input) {
        return input == null ? null : new CanonicalIr.Http(input.method(), input.path());
    }

    private CanonicalIr.EventSchema mapEventSchema(SpecDocument.EventSchema input) {
        return input == null ? null : new CanonicalIr.EventSchema(input.format(), input.version());
    }
}
