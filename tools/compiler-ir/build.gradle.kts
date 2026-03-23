plugins {
    `java-library`
}

dependencies {
    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
