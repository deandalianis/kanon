plugins {
    base
}

allprojects {
    group = "io.kanon"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension>("java") {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
            withJavadocJar()
            withSourcesJar()
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}
