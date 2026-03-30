plugins {
    `java-library`
}

dependencies {
    implementation(project(":tools:spec-model"))
    implementation(project(":tools:codebase-model"))
    implementation(libs.neo4j.driver)

    testImplementation(project(":tools:spec-compiler"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
