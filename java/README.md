# minigraf-jvm

[![Maven Central](https://img.shields.io/maven-central/v/io.github.project-minigraf/minigraf-jvm.svg)](https://central.sonatype.com/artifact/io.github.project-minigraf/minigraf-jvm)
[![License: MIT OR Apache-2.0](https://img.shields.io/badge/license-MIT%20OR%20Apache--2.0-blue.svg)](https://github.com/project-minigraf/minigraf#license)

> Embedded bi-temporal graph database for Java/Kotlin — Datalog queries, time travel, fat JAR with embedded natives

Minigraf for Java and Kotlin on the desktop JVM. Fat JAR with embedded native libraries. No Rust toolchain required.

## Supported platforms

| OS | Architectures | Notes |
|---|---|---|
| Linux (glibc) | x86_64, aarch64 | glibc 2.17 or newer (RHEL/CentOS 7+, Debian 8+, Ubuntu 14.04+) |
| Linux (musl) | x86_64, aarch64 | Alpine |
| macOS | x86_64, aarch64 | universal2 binary |
| Windows | x86_64, aarch64 | |

On any other platform, the first call fails with an `UnsupportedOperationException` naming the detected OS and architecture.

## Install

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("io.github.project-minigraf:minigraf-jvm:2.0.2")
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.project-minigraf</groupId>
    <artifactId>minigraf-jvm</artifactId>
    <version>2.0.2</version>
</dependency>
```

## Quick start

```kotlin
import io.github.project_minigraf.minigraf.MiniGrafDb
import org.json.JSONObject

// File-backed database
val db = MiniGrafDb.open("/path/to/myapp.graph")

// In-memory database (ephemeral / testing)
val mem = MiniGrafDb.openInMemory()

// Transact facts
db.execute("""(transact [[:alice :person/name "Alice"] [:alice :person/age 30]])""")

// Query with Datalog
val json = JSONObject(db.execute(
    "(query [:find ?name ?age :where [?e :person/name ?name] [?e :person/age ?age]])"
))
// json.getJSONArray("results").getJSONArray(0).getString(0) == "Alice"

// Time travel — state as of transaction 1
val snap = db.execute("(query [:find ?age :as-of 1 :where [:alice :person/age ?age]])")

// Flush dirty pages to disk
db.checkpoint()
```

### Java

Use the `Minigraf` facade for static factory methods. Without it, Java has to go through the Kotlin companion object (`MiniGrafDb.Companion.open(path)`).

```java
import io.github.project_minigraf.minigraf.Minigraf;
import io.github.project_minigraf.minigraf.MiniGrafDb;

MiniGrafDb db = Minigraf.open("/path/to/myapp.graph");
String result = db.execute("(transact [[:alice :person/name \"Alice\"]])");
db.checkpoint();
```

The bindings are generated Kotlin, so `kotlin-stdlib` is a transitive runtime dependency and ends up on Java classpaths too. JPMS users can require the automatic module `io.github.project_minigraf.minigraf`.

## Links

- [Full Java/JVM integration guide](https://github.com/project-minigraf/minigraf/wiki/Use-Cases#java--jvm)
- [Repository](https://github.com/project-minigraf/minigraf)
- [Datalog Reference](https://github.com/project-minigraf/minigraf/wiki/Datalog-Reference)

## License

MIT OR Apache-2.0
