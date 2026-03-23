plugins {
    application
}

dependencies {
    implementation(project(":tools:compiler-core"))
    implementation(project(":tools:compiler-dsl"))
    implementation(project(":tools:compiler-ir"))
    implementation(project(":tools:extractor-javaparser"))
    implementation(project(":tools:extractor-spoon"))
    implementation(project(":tools:graph-neo4j"))
    implementation(libs.picocli)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}

application {
    mainClass.set("io.kanon.specctl.cli.SpecctlMain")
}
