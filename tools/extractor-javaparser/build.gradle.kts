plugins {
    `java-library`
}

dependencies {
    implementation(project(":tools:compiler-core"))
    implementation(libs.javaparser.core)
    implementation(libs.javaparser.symbolsolver)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
