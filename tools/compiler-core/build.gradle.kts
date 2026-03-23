plugins {
    `java-library`
}

dependencies {
    implementation(project(":tools:compiler-dsl"))
    implementation(project(":tools:compiler-ir"))

    api(libs.jackson.databind)
    api(libs.jackson.yaml)
    api(libs.jackson.jsr310)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
