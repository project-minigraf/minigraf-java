#!/usr/bin/env bash
# Consumer test: a Gradle project that depends on minigraf-jvm only through its
# Maven coordinates runs the README quick start (Kotlin) and the consumer
# contract (Java) on this machine's JVM and native library.
#
#   run.sh local <m2-dir> <version>   from built artifacts (the dist-jar layout)
#   run.sh registry <version>         from Maven Central, once it has <version>
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$HERE")"
GROUP_PATH=io/github/project-minigraf/minigraf-jvm

# wait_for <seconds> <command...>: run the command every 30 s until it succeeds.
wait_for() {
  local limit="$1"; shift
  local start=$SECONDS
  until "$@"; do
    if (( SECONDS - start >= limit )); then
      echo "::error::gave up after ${limit}s waiting for: $*"
      return 1
    fi
    sleep 30
  done
}

ARGS=()
case "${1:-}" in
  local)
    REPO="$(cd "$2" && pwd)"
    VERSION="$3"
    # Gradle wants a URI; on Windows (Git Bash) turn /d/a/... into D:/a/...
    if command -v cygpath >/dev/null; then REPO="$(cygpath -m "$REPO")"; fi
    ARGS+=("-PminigrafRepo=$REPO")
    ;;
  registry)
    VERSION="$2"
    # Maven Central sync is the slowest of the registries (spec §6).
    wait_for 5400 curl -sfI -o /dev/null \
      "https://repo1.maven.org/maven2/$GROUP_PATH/$VERSION/minigraf-jvm-$VERSION.pom"
    ;;
  *)
    echo "usage: $0 local <m2-dir> <version> | registry <version>" >&2
    exit 2
    ;;
esac
ARGS+=("-PminigrafVersion=$VERSION")

gradle() { "$ROOT/java/gradlew" -p "$HERE" --no-daemon "${ARGS[@]}" "$@"; }

# The resolved dependency is the version under test, and its pom brings JNA.
DEPS="$(gradle -q dependencies --configuration runtimeClasspath)"
echo "$DEPS"
grep -q "io.github.project-minigraf:minigraf-jvm:$VERSION\b" <<<"$DEPS" \
  || { echo "::error::minigraf-jvm:$VERSION is not on the runtime classpath"; exit 1; }
grep -q "net.java.dev.jna:jna:" <<<"$DEPS" \
  || { echo "::error::minigraf-jvm does not bring JNA"; exit 1; }

gradle test
