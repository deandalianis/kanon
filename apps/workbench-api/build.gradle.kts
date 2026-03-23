plugins {
    alias(libs.plugins.spring.boot)
    java
}

dependencies {
    implementation(project(":tools:compiler-core"))
    implementation(project(":tools:compiler-dsl"))
    implementation(project(":tools:compiler-ir"))
    implementation(project(":tools:extractor-javaparser"))
    implementation(project(":tools:extractor-spoon"))
    implementation(project(":tools:graph-neo4j"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.springdoc.openapi.starter.webmvc)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.yaml)

    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.h2)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.assertj.core)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
