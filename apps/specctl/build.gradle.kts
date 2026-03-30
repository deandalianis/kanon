plugins {
    application
}

dependencies {
    implementation(project(":tools:codebase-extractor"))
    implementation(project(":tools:codebase-model"))
    implementation(project(":tools:graph-neo4j"))
    implementation(project(":tools:spec-compiler"))
    implementation(project(":tools:spec-model"))
    implementation(libs.picocli)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}

application {
    mainClass.set("io.kanon.specctl.cli.SpecctlMain")
}
