package io.kanon.specctl.core.plugin;

import io.kanon.specctl.ir.CanonicalIr;

import java.util.stream.Collectors;

public final class MessagingPlugin implements CompilerPlugin {
    @Override
    public String name() {
        return "messaging";
    }

    @Override
    public int order() {
        return ExecutionPhase.RUNTIME.order();
    }

    @Override
    public void apply(GenerationContext context) {
        if (!context.ir().generation().targets().kafkaPublishers() && !context.ir().generation().targets().kafkaConsumers()) {
            return;
        }
        String packageBase = context.ir().service().basePackage() + ".generated.messaging";
        for (CanonicalIr.BoundedContext boundedContext : context.ir().boundedContexts()) {
            for (CanonicalIr.Aggregate aggregate : boundedContext.aggregates()) {
                for (CanonicalIr.Event event : aggregate.events()) {
                    String eventRecord = PluginSupport.provenanceHeader(context)
                            + "package " + packageBase + ";" + System.lineSeparator()
                            + "public record " + event.name() + "("
                            + event.payload().stream().map(field -> PluginSupport.mapType(field.type()) + " " + field.name()).collect(Collectors.joining(", "))
                            + ") {}" + System.lineSeparator();
                    context.writeFile("src/generated/java/messaging", packageBase.replace('.', '/') + "/" + event.name() + ".java", eventRecord);

                    String publisher = PluginSupport.provenanceHeader(context)
                            + "package " + packageBase + ";" + System.lineSeparator()
                            + "@org.springframework.stereotype.Component" + System.lineSeparator()
                            + "public class " + event.name() + "Publisher {" + System.lineSeparator()
                            + "    private final org.springframework.kafka.core.KafkaTemplate<String, Object> kafkaTemplate;" + System.lineSeparator()
                            + "    public " + event.name() + "Publisher(org.springframework.kafka.core.KafkaTemplate<String, Object> kafkaTemplate) {" + System.lineSeparator()
                            + "        this.kafkaTemplate = kafkaTemplate;" + System.lineSeparator()
                            + "    }" + System.lineSeparator()
                            + "    public void publish(" + event.name() + " event) { kafkaTemplate.send(\"" + event.topic() + "\", event); }" + System.lineSeparator()
                            + "}" + System.lineSeparator();
                    context.writeFile("src/generated/java/messaging", packageBase.replace('.', '/') + "/" + event.name() + "Publisher.java", publisher);

                    if (context.ir().generation().targets().kafkaConsumers()) {
                        String consumer = PluginSupport.provenanceHeader(context)
                                + "package " + packageBase + ";" + System.lineSeparator()
                                + "@org.springframework.stereotype.Component" + System.lineSeparator()
                                + "public class " + event.name() + "Consumer {" + System.lineSeparator()
                                + "    @org.springframework.kafka.annotation.KafkaListener(topics = \"" + event.topic() + "\", groupId = \"" + context.ir().service().name() + "\")" + System.lineSeparator()
                                + "    public void onMessage(" + event.name() + " event) {}" + System.lineSeparator()
                                + "}" + System.lineSeparator();
                        context.writeFile("src/generated/java/messaging", packageBase.replace('.', '/') + "/" + event.name() + "Consumer.java", consumer);
                    }
                }
            }
        }
    }
}
