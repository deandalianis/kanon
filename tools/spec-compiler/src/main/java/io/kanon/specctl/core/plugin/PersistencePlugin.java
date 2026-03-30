package io.kanon.specctl.core.plugin;

import io.kanon.specctl.ir.CanonicalIr;
import java.util.stream.Collectors;

public final class PersistencePlugin implements CompilerPlugin {
    @Override
    public String name() {
        return "persistence";
    }

    @Override
    public int order() {
        return ExecutionPhase.RUNTIME.order();
    }

    @Override
    public void apply(GenerationContext context) {
        if (!context.ir().generation().targets().persistenceJpa()) {
            return;
        }
        String packageBase = context.ir().service().basePackage() + ".generated.persistence";
        int migrationVersion = 1;
        for (CanonicalIr.BoundedContext boundedContext : context.ir().boundedContexts()) {
            for (CanonicalIr.Aggregate aggregate : boundedContext.aggregates()) {
                if (aggregate.entities().isEmpty()) {
                    continue;
                }
                CanonicalIr.Entity entity = aggregate.entities().getFirst();
                String entityName = aggregate.name() + "JpaEntity";
                StringBuilder source = new StringBuilder();
                source.append(PluginSupport.provenanceHeader(context));
                source.append("package ").append(packageBase).append(";").append(System.lineSeparator());
                source.append("@jakarta.persistence.Entity").append(System.lineSeparator());
                source.append("@jakarta.persistence.Table(name = \"").append(entity.table()).append("\")")
                        .append(System.lineSeparator());
                source.append("public class ").append(entityName).append(" {").append(System.lineSeparator());
                for (CanonicalIr.Field field : entity.fields()) {
                    if (field.pk()) {
                        source.append("    @jakarta.persistence.Id").append(System.lineSeparator());
                    }
                    source.append("    private ").append(PluginSupport.mapType(field.type())).append(" ")
                            .append(field.name()).append(";").append(System.lineSeparator());
                }
                source.append("}").append(System.lineSeparator());
                context.writeFile("src/generated/java/persistence",
                        packageBase.replace('.', '/') + "/" + entityName + ".java", source.toString());

                String repositorySource = PluginSupport.provenanceHeader(context)
                        + "package " + packageBase + ";" + System.lineSeparator()
                        + "public interface " + aggregate.name() +
                        "Repository extends org.springframework.data.jpa.repository.JpaRepository<"
                        + entityName + ", java.util.UUID> {}" + System.lineSeparator();
                context.writeFile("src/generated/java/persistence",
                        packageBase.replace('.', '/') + "/" + aggregate.name() + "Repository.java", repositorySource);

                String columns = entity.fields().stream()
                        .map(field -> "  " + field.name() + " " + PluginSupport.sqlType(field.type()) +
                                (field.pk() ? " primary key" : ""))
                        .collect(Collectors.joining("," + System.lineSeparator()));
                String migration =
                        "create table if not exists " + entity.table() + " (" + System.lineSeparator() + columns +
                                System.lineSeparator() + ");" + System.lineSeparator();
                context.writeFile("src/generated/resources/db/migration",
                        "V" + migrationVersion++ + "__" + entity.table() + ".sql", migration);
            }
        }
    }
}
