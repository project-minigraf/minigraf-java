#!/usr/bin/env bash
# Run in disposable binding/core checkouts. No benchmark executes during build.
set -euo pipefail
binding_dir=$(cd "$(dirname "$0")/../.." && pwd)
core_dir=$(cd "${1:?usage: build.sh CORE_CHECKOUT}" && pwd)
cd "$binding_dir"
core_revision=${CORE_REVISION:-$(git -C "$core_dir" rev-parse HEAD)}
binding_revision=${BINDING_REVISION:-$(git rev-parse HEAD)}
export CARGO_BUILD_JOBS=${CARGO_BUILD_JOBS:-8}
test ! -e .cargo/config.toml
mkdir -p .cargo bench/manual/build/evidence
python3 - "$core_dir" <<'PY'
import json, pathlib, sys
pathlib.Path('.cargo/config.toml').write_text(
    '[patch."https://github.com/project-minigraf/minigraf"]\n'
    'minigraf = { path = ' + json.dumps(sys.argv[1]) + ' }\n')
PY
cargo update -p minigraf
cargo metadata --format-version 1 > bench/manual/build/evidence/cargo-metadata.json
python3 - "$core_dir" <<'PY'
import json, pathlib, sys
p = [p for p in json.load(open('bench/manual/build/evidence/cargo-metadata.json'))['packages'] if p['name'] == 'minigraf']
assert len(p) == 1 and p[0]['source'] is None
assert pathlib.Path(p[0]['manifest_path']).parent == pathlib.Path(sys.argv[1])
PY
cargo test --lib
cargo build --release --lib --bin uniffi-bindgen
# Separate invocation: copyLocalNative writes processResources inputs without
# declaring a Gradle dependency, so selecting both in one graph is rejected.
bash java/gradlew -p java --no-daemon --max-workers 8 copyLocalNative
bash java/gradlew -p java --no-daemon --max-workers 8 test jar
bash java/gradlew -p bench/manual --no-daemon test installDist
python3 - "$core_revision" "$binding_revision" <<'PY'
import hashlib, json, pathlib, subprocess, sys
root = pathlib.Path('bench/manual')
digest = hashlib.sha256()
for p in sorted(p for p in root.rglob('*') if p.is_file() and
    not any(part in ('build', '.gradle', 'results') for part in p.relative_to(root).parts)):
    digest.update(str(p.relative_to(root)).encode() + b'\0' + p.read_bytes() + b'\0')
def sha(p): return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
record = dict(core_revision=sys.argv[1], binding_revision=sys.argv[2],
    evidence_kind='source-build', harness_sha256=digest.hexdigest(),
    binding_jar_sha256=sha('java/build/libs/minigraf-jvm-0.0.0-local.jar'),
    native_sha256=sha('target/release/libminigraf_ffi.so'),
    cargo_lock_sha256=sha('Cargo.lock'),
    rustc=subprocess.check_output(['rustc','--version'], text=True).strip(),
    java=subprocess.check_output(['java','-version'], stderr=subprocess.STDOUT, text=True).splitlines()[0],
    profile='release', abi='x86_64-unknown-linux-gnu', gradle='8.11',
    kotlin='2.0.21', jna='5.14.0', uniffi='0.31.1', json='20240303')
(root / 'build/evidence/build.json').write_text(json.dumps(record,indent=2)+'\n')
PY
cp Cargo.lock bench/manual/build/evidence/Cargo.lock
