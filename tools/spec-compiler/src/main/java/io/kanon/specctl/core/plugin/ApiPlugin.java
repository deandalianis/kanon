package io.kanon.specctl.core.plugin;

import io.kanon.specctl.ir.CanonicalIr;
import java.util.Optional;

public final class ApiPlugin implements CompilerPlugin {
    @Override
    public String name() {
        return "api";
    }

    @Override
    public int order() {
        return ExecutionPhase.RUNTIME.order();
    }

    @Override
    public void apply(GenerationContext context) {
        if (!context.ir().generation().targets().springMvcControllers()) {
            return;
        }
        String packageBase = context.ir().service().basePackage() + ".generated.api";
        String runtimePackage = context.ir().service().basePackage() + ".generated.runtime";
        String domainPackage = context.ir().service().basePackage() + ".generated.domain";
        for (CanonicalIr.BoundedContext boundedContext : context.ir().boundedContexts()) {
            for (CanonicalIr.Aggregate aggregate : boundedContext.aggregates()) {
                String className = aggregate.name() + "Controller";
                StringBuilder source = new StringBuilder();
                source.append(PluginSupport.provenanceHeader(context));
                source.append("package ").append(packageBase).append(";").append(System.lineSeparator());
                source.append("@org.springframework.web.bind.annotation.RestController").append(System.lineSeparator());
                source.append("@org.springframework.web.bind.annotation.RequestMapping(\"/")
                        .append(aggregate.canonicalName().replace('_', '-')).append("\")")
                        .append(System.lineSeparator());
                source.append("public class ").append(className).append(" {").append(System.lineSeparator());
                source.append("    private final ").append(runtimePackage).append(".").append(aggregate.name())
                        .append("CommandHandler handler;").append(System.lineSeparator());
                source.append("    public ").append(className).append("(").append(runtimePackage).append(".")
                        .append(aggregate.name()).append("CommandHandler handler) {").append(System.lineSeparator());
                source.append("        this.handler = handler;").append(System.lineSeparator());
                source.append("    }").append(System.lineSeparator());
                for (CanonicalIr.Command command : aggregate.commands()) {
                    Optional<String> securityExpression =
                            PluginSupport.securityExpression(context.ir(), command.name());
                    securityExpression.ifPresent(expression -> source.append(
                                    "    @org.springframework.security.access.prepost.PreAuthorize(\"")
                            .append(expression.replace("\"", "\\\""))
                            .append("\")").append(System.lineSeparator()));
                    source.append("    ").append(mapping(command.http().method())).append("(\"")
                            .append(command.http().path()).append("\")").append(System.lineSeparator());
                    source.append("    public org.springframework.http.ResponseEntity<Void> ")
                            .append(PluginSupport.lowerFirst(command.name())).append("(");
                    if (!command.inputFields().isEmpty()) {
                        source.append("@jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody ")
                                .append(domainPackage).append(".").append(command.name()).append("Request request");
                    }
                    source.append(") {").append(System.lineSeparator());
                    if (!command.inputFields().isEmpty()) {
                        source.append("        handler.").append(PluginSupport.lowerFirst(command.name()))
                                .append("(request);").append(System.lineSeparator());
                    } else {
                        source.append("        handler.").append(PluginSupport.lowerFirst(command.name()))
                                .append("(new ")
                                .append(domainPackage).append(".").append(command.name())
                                .append("Request(java.util.Map.of()));").append(System.lineSeparator());
                    }
                    source.append("        return org.springframework.http.ResponseEntity.noContent().build();")
                            .append(System.lineSeparator());
                    source.append("    }").append(System.lineSeparator());
                }
                source.append("}").append(System.lineSeparator());
                context.writeFile("src/generated/java/api", packageBase.replace('.', '/') + "/" + className + ".java",
                        source.toString());
            }
        }
    }

    private String mapping(String method) {
        return switch (method) {
            case "GET" -> "@org.springframework.web.bind.annotation.GetMapping";
            case "PUT" -> "@org.springframework.web.bind.annotation.PutMapping";
            case "DELETE" -> "@org.springframework.web.bind.annotation.DeleteMapping";
            default -> "@org.springframework.web.bind.annotation.PostMapping";
        };
    }
}
