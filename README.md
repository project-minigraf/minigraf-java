# minigraf-java

JVM binding for [Minigraf](https://github.com/project-minigraf/minigraf) — zero-config,
single-file, embedded bi-temporal graph database with Datalog queries.

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("io.github.project-minigraf:minigraf-jvm:1.1.1")
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.project-minigraf</groupId>
    <artifactId>minigraf-jvm</artifactId>
    <version>1.1.1</version>
</dependency>
```

## Quick start

```kotlin
import io.github.project_minigraf.minigraf.MiniGrafDb

val db = MiniGrafDb.openInMemory()
val result = db.execute("""(transact [[:alice :name "Alice"]])""")
println(result)  // {"transacted":1}
```

## Building from source

Requires Rust stable toolchain and JDK 17.

```bash
cargo build --release
cd java
./gradlew generateKotlinBindings copyLocalNative test
```

## Cascade release

This repo receives a `core-release` repository_dispatch from the minigraf monorepo
cascade whenever a new version of the `minigraf` core crate is published. The release
workflow pins the new version, commits, tags, builds native libraries for all four
platforms, and publishes the fat JAR to Maven Central.

## License

MIT OR Apache-2.0
