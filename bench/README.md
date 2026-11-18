# JVM benchmarks

`manual/` keeps the #839 smoke/benchmark interface and timing boundaries.
The #858 nightly adapter reuses that installed harness; it does not build or
patch Cargo. From the binding root on Linux x86_64 (glibc):

```sh
# JAVA_HOME: Eclipse Temurin 17.0.17+10; WORKFLOW_SHA: actual core workflow SHA.
export BENCH_SHA=$(git -C "$CORE_CHECKOUT" rev-parse HEAD)
CARGO_BUILD_JOBS=4 CORE_REVISION="$BENCH_SHA" \
  bash bench/manual/build.sh "$CORE_CHECKOUT"
bash bench/nightly.sh --test
bash bench/nightly.sh
```

The builder alone owns the measured-core Cargo patch and verifies its resolved
source. The adapter checks the build identities against `BENCH_SHA` and binding
HEAD, and requires `WORKFLOW_SHA` and `JAVA_HOME`. It rejects JVM/native override
environment variables and validates the measured JVM's vendor, full version,
and actual flags: `-Xms512m -Xmx512m -XX:+UseG1GC`.

Smoke creates fresh `smoke/` raw checks without timers, metrics or provenance.
Timing creates fresh `benchmark/samples.jsonl`, retains the native resource from
the installed binding JAR as `benchmark/libminigraf_ffi.so` (verified against the
build), and writes `binding-provenance.json` and `binding-results.json` in the
binding root. Each of the 14 latency values is the median of ten sample means
in **nanoseconds**. Short cases use 100 warmups and 20 operations per sample;
the two write cases use five warmups and one operation per sample. Startup is
outside timing; JNA/conversion/callback costs remain included. Warmups do not
establish JIT stationarity.

Only a successful process exit, teardown and complete validated stream produce
the 14 `ok:` lines. Existing output directories or metric/provenance files fail;
archive/remove a previous run's outputs explicitly before another invocation.
The core workflow owns upload/audit and pins the Ubuntu 24.04 testbed. See its
[`docs/BINDING_BENCHMARKS.md`](https://github.com/project-minigraf/minigraf/blob/v3/docs/BINDING_BENCHMARKS.md)
for the authoritative IDs, timing boundaries and provenance schema.

Tests (also run by the existing PR Java job after building the binding JAR):

```sh
bash java/gradlew -p bench/manual --no-daemon test
python3 -I -m unittest discover -s bench/tests -v
```
