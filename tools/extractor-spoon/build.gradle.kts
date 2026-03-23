plugins {
    `java-library`
}

dependencies {
    implementation(project(":tools:compiler-core"))
    implementation(libs.spoon.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
