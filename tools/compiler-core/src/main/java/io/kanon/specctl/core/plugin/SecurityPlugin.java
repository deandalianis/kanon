package io.kanon.specctl.core.plugin;

public final class SecurityPlugin implements CompilerPlugin {
    @Override
    public String name() {
        return "security";
    }

    @Override
    public int order() {
        return ExecutionPhase.RUNTIME.order();
    }

    @Override
    public void apply(GenerationContext context) {
        if (context.ir().security() == null) {
            return;
        }
        String packageBase = context.ir().service().basePackage() + ".generated.security";
        String roles = context.ir().security().roles().stream()
                .map(role -> "    public static final String " + role + " = \"" + role + "\";")
                .reduce("", (left, right) -> left + right + System.lineSeparator());
        String source = PluginSupport.provenanceHeader(context)
                + "package " + packageBase + ";" + System.lineSeparator()
                + "@org.springframework.context.annotation.Configuration" + System.lineSeparator()
                + "@org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity" + System.lineSeparator()
                + "public class GeneratedSecurityConfig {" + System.lineSeparator()
                + roles
                + "}" + System.lineSeparator();
        context.writeFile("src/generated/java/security", packageBase.replace('.', '/') + "/GeneratedSecurityConfig.java", source);
    }
}
