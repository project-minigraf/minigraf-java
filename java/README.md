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
    implementation("io.github.project-minigraf:minigraf-jvm:2.0.8")
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.project-minigraf</groupId>
    <artifactId>minigraf-jvm</artifactId>
    <version>2.0.8</version>
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

## Open options, cursors, the fact log and the log writer

```java
import io.github.project_minigraf.minigraf.*;

// Read-only: shared lock, nothing written; writes fail with API-014.
MiniGrafDb src = Minigraf.openWithOptions("old.graph",
        Minigraf.options().readOnly(true).pageCacheSize(4096).build());

// A cursor's answer is fixed when it opens. Each batch is a JSON array of rows,
// encoded like execute()'s "results".
try (MiniGrafCursor cursor = src.query("(query [:find ?n :where [?e :name ?n]])")) {
    for (String batch : Minigraf.batches(cursor, 1000)) { /* parse rows */ }
}

// Copy every fact version, keeping tx and valid-time bounds, into a new file.
try (MiniGrafFactLog log = src.factLog(Minigraf.filter().build());
     MiniGrafLogWriter out = Minigraf.createLogWriter("new.graph", Minigraf.options().build())) {
    List<FactRecord> batch = new ArrayList<>();
    for (FactRecord rec : Minigraf.records(log, 1000)) {
        if (!rec.getAttribute().startsWith(":secret/")) batch.add(rec);
    }
    out.appendBatch(batch);
    out.advanceTxCount(src.currentTxCount());
    out.finish();
}   // close() without finish() abandons the build and leaves no file
```

`Minigraf.options().queryMemoryLimit(bytes)` caps the bytes one query may hold, counted
per entry by the operators that keep state (join build sides, negation and `or` tables,
distinct sets, aggregate groups, window rows, rule-derived facts). A query that would
hold more fails with QRY-012 and frees what it held. The default, and `Long.MAX_VALUE`,
is no limit. Rows a cursor returns are not counted, so read a large answer with `query()`.

Kotlin callers can use `cursor.batches()` and `log.records()` directly, and the
generated constructors with named arguments (`OpenOptions(readOnly = true)`).

All UniFFI objects are `AutoCloseable`: `close()` frees the native object. The
shim's own close methods are named `release()` (cursor, fact log) and `abandon()`
(log writer) here, because `close()` is taken.

A record's value is a `MiniGrafValue` (`Text`, `Int64`, `Float64`, `Bool`, `Ref`,
`Keyword`, `Null`), so a ref and a string stay different. `Minigraf.VALID_TIME_FOREVER`
is the `validTo` of a fact valid forever. Errors are `MiniGrafException`;
`Minigraf.errorMessage(e)` gives the text starting with its code, such as `[API-015]`.

## Prepared queries

`db.prepare(text)` parses a query once; run it with its `$slots` bound (names without
the `$`). Each run reads the database as it is then, and a prepared query keeps its
database open until both are closed.

```java
MiniGrafPreparedQuery pq = db.prepare("(query [:find ?n :where [?e :age $age] [?e :name ?n]])");
String thirty = Minigraf.execute(pq, Map.of("age", 30));
try (MiniGrafCursor cursor = Minigraf.query(pq, Map.of("age", 40))) { /* batches */ }
```

```kotlin
val thirty = pq.execute("age" to 30)
pq.query("age" to 40).use { cursor -> cursor.batches().forEach { /* rows */ } }
```

`Boolean`, `Byte`/`Short`/`Int`/`Long`, `Float`/`Double`, `String`, `null` and
`java.util.UUID` (an entity, which also fits value slots as a ref) bind as they are.
For the other kinds pass a `MiniGrafValue` (`Keyword`, `Ref`, a whole-number `Float64`),
or `Minigraf.entity(uuid)`, `Minigraf.txCount(n)` / `Minigraf.timestamp(ms)` for
`:as-of`, and `Minigraf.ANY_VALID_TIME` for `:valid-at`. Any other type throws
`IllegalArgumentException`. A missing slot is `MiniGrafException.Api` with `[API-022]`,
a slot in an attribute position `[API-023]`, and a value of the wrong kind `[API-024]`.

A `Set` binds a set value, for `in?` and the other set predicates or a set-valued
attribute: `pq.execute("ids" to setOf(1, 2, 3))`, or `Set.of(1, 2, 3)` from Java. Its
elements are the plain values above or a `MiniGrafValue` (`MiniGrafValue.Keyword(":role/admin")`),
and a `UUID` element is a ref. A set inside a set is `MiniGrafException.Api` with
`[API-021]`; another kind of element, or a `List`, throws `IllegalArgumentException`. Set
values come back as JSON arrays from `execute` and as `MiniGrafValue.Set` in fact records.

Errors are `MiniGrafException.Parse`, `.Query`, `.Storage`, `.Api` (a bad argument, a
closed object, a bind mistake) or `.Other`.

## Links

- [Full Java/JVM integration guide](https://github.com/project-minigraf/minigraf/wiki/Use-Cases#java--jvm)
- [Repository](https://github.com/project-minigraf/minigraf)
- [Datalog Reference](https://github.com/project-minigraf/minigraf/wiki/Datalog-Reference)

## License

MIT OR Apache-2.0
