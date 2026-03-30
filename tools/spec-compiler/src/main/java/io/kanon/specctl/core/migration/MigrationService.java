package io.kanon.specctl.core.migration;

import io.kanon.specctl.dsl.MigrationDocument;
import io.kanon.specctl.dsl.SpecDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class MigrationService {
    public MigrationOutcome plan(SpecDocument spec, MigrationDocument migrations) {
        return applyInternal(spec, migrations, true);
    }

    public MigrationOutcome apply(SpecDocument spec, MigrationDocument migrations, boolean dryRun) {
        return applyInternal(spec, migrations, dryRun);
    }

    private MigrationOutcome applyInternal(SpecDocument spec, MigrationDocument migrations, boolean dryRun) {
        List<String> actions = new ArrayList<>();
        SpecDocument current = spec;
        for (MigrationDocument.Migration migration : migrations.migrations()) {
            actions.add(describe(migration));
            if (dryRun) {
                continue;
            }
            current = switch (migration.type()) {
                case RENAME_FIELD -> renameField(current, migration);
                case RENAME_AGGREGATE -> renameAggregate(current, migration);
                case RENAME_COMMAND -> renameCommand(current, migration);
                case RENAME_EVENT -> renameEvent(current, migration);
                case REWRITE_RULE_EXPRESSION -> rewriteRules(current, migration);
            };
        }
        return new MigrationOutcome(current, List.copyOf(actions), dryRun);
    }

    private String describe(MigrationDocument.Migration migration) {
        return migration.type() + ": " + migration.from() + " -> " + migration.to();
    }

    private SpecDocument renameField(SpecDocument spec, MigrationDocument.Migration migration) {
        List<SpecDocument.BoundedContext> contexts = new ArrayList<>();
        boolean applied = false;
        for (SpecDocument.BoundedContext context : spec.boundedContexts()) {
            List<SpecDocument.Aggregate> aggregates = new ArrayList<>();
            for (SpecDocument.Aggregate aggregate : context.aggregates()) {
                if (!matches(migration.boundedContext(), context.name()) ||
                        !matches(migration.aggregate(), aggregate.name())) {
                    aggregates.add(aggregate);
                    continue;
                }
                List<SpecDocument.Entity> entities = new ArrayList<>();
                for (SpecDocument.Entity entity : aggregate.entities()) {
                    if (!matches(migration.entity(), entity.name())) {
                        entities.add(entity);
                        continue;
                    }
                    List<SpecDocument.Field> fields = entity.fields().stream()
                            .map(field -> field.name().equals(migration.from()) ? field.withName(migration.to()) :
                                    field)
                            .toList();
                    if (fields.stream().noneMatch(field -> field.name().equals(migration.to()))) {
                        throw new IllegalStateException("Ambiguous field migration for " + migration.from());
                    }
                    entities.add(entity.withFields(fields));
                    applied = true;
                }
                aggregates.add(aggregate.withEntities(entities));
            }
            contexts.add(context.withAggregates(aggregates));
        }
        if (!applied) {
            throw new IllegalStateException("Migration did not match any field: " + migration.from());
        }
        return spec.withBoundedContexts(contexts);
    }

    private SpecDocument renameAggregate(SpecDocument spec, MigrationDocument.Migration migration) {
        List<SpecDocument.BoundedContext> contexts = spec.boundedContexts().stream()
                .map(context -> {
                    if (!matches(migration.boundedContext(), context.name())) {
                        return context;
                    }
                    List<SpecDocument.Aggregate> aggregates = context.aggregates().stream()
                            .map(aggregate -> aggregate.name().equals(migration.from()) ?
                                    aggregate.withName(migration.to()) : aggregate)
                            .toList();
                    return context.withAggregates(aggregates);
                })
                .toList();
        return spec.withBoundedContexts(contexts);
    }

    private SpecDocument renameCommand(SpecDocument spec, MigrationDocument.Migration migration) {
        List<SpecDocument.BoundedContext> contexts = spec.boundedContexts().stream()
                .map(context -> {
                    List<SpecDocument.Aggregate> aggregates = context.aggregates().stream()
                            .map(aggregate -> {
                                if (!matches(migration.aggregate(), aggregate.name())) {
                                    return aggregate;
                                }
                                List<SpecDocument.Command> commands = aggregate.commands().stream()
                                        .map(command -> command.name().equals(migration.from()) ?
                                                command.withName(migration.to()) : command)
                                        .toList();
                                return aggregate.withCommands(commands);
                            })
                            .toList();
                    return context.withAggregates(aggregates);
                })
                .toList();
        return spec.withBoundedContexts(contexts);
    }

    private SpecDocument renameEvent(SpecDocument spec, MigrationDocument.Migration migration) {
        List<SpecDocument.BoundedContext> contexts = spec.boundedContexts().stream()
                .map(context -> {
                    List<SpecDocument.Aggregate> aggregates = context.aggregates().stream()
                            .map(aggregate -> {
                                if (!matches(migration.aggregate(), aggregate.name())) {
                                    return aggregate;
                                }
                                List<SpecDocument.Event> events = aggregate.events().stream()
                                        .map(event -> event.name().equals(migration.from()) ?
                                                event.withName(migration.to()) : event)
                                        .toList();
                                List<SpecDocument.Command> commands = aggregate.commands().stream()
                                        .map(command -> new SpecDocument.Command(
                                                command.name(),
                                                command.http(),
                                                command.input(),
                                                command.rules(),
                                                command.emits().stream()
                                                        .map(name -> name.equals(migration.from()) ? migration.to() :
                                                                name)
                                                        .collect(Collectors.toList()),
                                                command.scenarios()
                                        ))
                                        .toList();
                                return aggregate.withEvents(events).withCommands(commands);
                            })
                            .toList();
                    return context.withAggregates(aggregates);
                })
                .toList();
        return spec.withBoundedContexts(contexts);
    }

    private SpecDocument rewriteRules(SpecDocument spec, MigrationDocument.Migration migration) {
        List<SpecDocument.BoundedContext> contexts = spec.boundedContexts().stream()
                .map(context -> {
                    List<SpecDocument.Aggregate> aggregates = context.aggregates().stream()
                            .map(aggregate -> {
                                if (!matches(migration.aggregate(), aggregate.name())) {
                                    return aggregate;
                                }
                                List<SpecDocument.Command> commands = aggregate.commands().stream()
                                        .map(command -> {
                                            if (!matches(migration.command(), command.name())) {
                                                return command;
                                            }
                                            List<SpecDocument.Rule> rules = command.rules().stream()
                                                    .map(rule -> rule.withEnsure(
                                                            rule.ensure().replace(migration.from(), migration.to())))
                                                    .toList();
                                            return command.withRules(rules);
                                        })
                                        .toList();
                                return aggregate.withCommands(commands);
                            })
                            .toList();
                    return context.withAggregates(aggregates);
                })
                .toList();
        return spec.withBoundedContexts(contexts);
    }

    private boolean matches(String expected, String actual) {
        return expected == null || expected.isBlank() ||
                expected.toLowerCase(Locale.ROOT).equals(actual.toLowerCase(Locale.ROOT));
    }

    public record MigrationOutcome(SpecDocument updatedSpec, List<String> actions, boolean dryRun) {
    }
}
