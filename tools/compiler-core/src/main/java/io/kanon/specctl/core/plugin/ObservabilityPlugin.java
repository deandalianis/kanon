package io.kanon.specctl.core.plugin;

public final class ObservabilityPlugin implements CompilerPlugin {
    @Override
    public String name() {
        return "observability";
    }

    @Override
    public int order() {
        return ExecutionPhase.RUNTIME.order();
    }

    @Override
    public void apply(GenerationContext context) {
        String packageBase = context.ir().service().basePackage() + ".generated.obs";
        String source = PluginSupport.provenanceHeader(context)
                + "package " + packageBase + ";" + System.lineSeparator()
                + "public final class GeneratedObservability {" + System.lineSeparator()
                + "    public static final String COMMAND_DURATION = \"specctl.command.duration\";" + System.lineSeparator()
                + "    public static final String RULE_VIOLATION = \"specctl.rule.violation\";" + System.lineSeparator()
                + "    public static final String KAFKA_PUBLISH = \"specctl.kafka.publish\";" + System.lineSeparator()
                + "    private GeneratedObservability() {}" + System.lineSeparator()
                + "}" + System.lineSeparator();
        context.writeFile("src/generated/java/obs", packageBase.replace('.', '/') + "/GeneratedObservability.java", source);
    }
}
