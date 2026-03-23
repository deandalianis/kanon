plugins {
    `java-library`
}

dependencies {
    implementation(project(":tools:compiler-core"))
    implementation(project(":tools:compiler-ir"))
    implementation(libs.neo4j.driver)

    testImplementation(project(":tools:compiler-dsl"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
