plugins {
    alias(libs.plugins.spring.boot)
    java
}

dependencies {
    implementation(project(":tools:codebase-extractor"))
    implementation(project(":tools:codebase-model"))
    implementation(project(":tools:graph-neo4j"))
    implementation(project(":tools:spec-compiler"))
    implementation(project(":tools:spec-model"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.springdoc.openapi.starter.webmvc)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.yaml)
    implementation(libs.jackson.jsr310)

    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.h2)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.assertj.core)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
