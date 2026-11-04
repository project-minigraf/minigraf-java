plugins {
    kotlin("jvm") version "2.0.21"
    application
}
repositories { mavenCentral() }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(files("../../java/build/libs/minigraf-jvm-0.0.0-local.jar"))
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
application {
    mainClass.set("io.github.project_minigraf.benchmark.BenchmarkKt")
    applicationDefaultJvmArgs = listOf("-Xms512m", "-Xmx512m", "-XX:+UseG1GC")
}
