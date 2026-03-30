plugins {
    `java-library`
}

dependencies {
    implementation(project(":tools:codebase-model"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)
    implementation(libs.asm.core)
    implementation(libs.asm.tree)
    implementation(libs.classgraph)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
