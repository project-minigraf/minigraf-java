plugins {
    kotlin("jvm") version "2.0.21"
}

val minigrafVersion: String = providers.gradleProperty("minigrafVersion").get()

dependencies {
    implementation("io.github.project-minigraf:minigraf-jvm:$minigrafVersion")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Whatever JDK runs Gradle (17 or later) compiles and runs the tests.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
