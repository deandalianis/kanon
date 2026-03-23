package io.kanon.specctl.core.plugin;

public final class BootstrapPlugin implements CompilerPlugin {
    @Override
    public String name() {
        return "bootstrap";
    }

    @Override
    public int order() {
        return ExecutionPhase.MODEL.order();
    }

    @Override
    public void apply(GenerationContext context) {
        String packageBase = context.ir().service().basePackage() + ".generated.bootstrap";
        String appClass = context.ir().service().name().replace("-", "") + "GeneratedApplication";
        String appSource = PluginSupport.provenanceHeader(context)
                + "package " + packageBase + ";" + System.lineSeparator()
                + "@org.springframework.boot.autoconfigure.SpringBootApplication" + System.lineSeparator()
                + "public class " + appClass + " {" + System.lineSeparator()
                + "    public static void main(String[] args) {" + System.lineSeparator()
                + "        org.springframework.boot.SpringApplication.run(" + appClass + ".class, args);" + System.lineSeparator()
                + "    }" + System.lineSeparator()
                + "}" + System.lineSeparator();
        context.writeFile("src/generated/java/bootstrap", packageBase.replace('.', '/') + "/" + appClass + ".java", appSource);

        String buildFile = """
                plugins {
                    id("org.springframework.boot") version "3.3.0"
                    java
                }

                dependencies {
                    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.3.0"))
                    implementation("org.springframework.boot:spring-boot-starter-web")
                    implementation("org.springframework.boot:spring-boot-starter-validation")
                    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
                    implementation("org.springframework.boot:spring-boot-starter-security")
                    implementation("org.springframework.boot:spring-boot-starter-actuator")
                    implementation("org.springframework.kafka:spring-kafka")
                }
                """;
        context.writeFile("src/generated/build", "build.gradle.kts", buildFile);

        String dockerfile = """
                FROM eclipse-temurin:21-jdk
                WORKDIR /app
                COPY . .
                RUN ./gradlew test
                CMD ["./gradlew", "bootRun"]
                """;
        context.writeFile("src/generated/build", "Dockerfile", dockerfile);

        String applicationConfig = """
                spring:
                  application:
                    name: %s
                management:
                  endpoints:
                    web:
                      exposure:
                        include: health,info,prometheus
                """.formatted(context.ir().service().name());
        context.writeFile("src/generated/resources/config", "application-generated.yml", applicationConfig);
    }
}
