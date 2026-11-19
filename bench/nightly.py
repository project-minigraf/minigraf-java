"""Fail-closed adapter for the unchanged #839 JVM harness (core #858 contract)."""
import hashlib
import json
import math
import os
from pathlib import Path
import re
import statistics
import subprocess
import sys
import zipfile

IDS = tuple('binding/jvm/' + suffix for suffix in (
    'prepared_execute/1k', 'cursor_batch_1/1k', 'cursor_batch_256/1k',
    'set_roundtrip/2', 'predicate/1k', 'aggregate/1k', 'prepare/point',
    'register_predicate/first', 'register_aggregate/first', 'fact_log_batch_256/1k',
    'options_error/negative_cache', 'options_readonly_open/1k', 'file_transact/1k',
    'log_writer_append_finish/1k',
))
FLAGS = ['-Xms512m', '-Xmx512m', '-XX:+UseG1GC']
IGNORED = {'build', '.gradle', 'results', '__pycache__'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def parse_json(text):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, f'duplicate JSON key: {key}')
            result[key] = value
        return result

    def constant(value):
        raise ValueError(f'nonfinite JSON constant: {value}')

    return json.loads(text, object_pairs_hook=pairs, parse_constant=constant)


def validate(records, mode, identity):
    require(mode in ('smoke', 'benchmark'), 'unknown mode')
    require(len(records) >= 2, 'missing stream')
    environment, complete = records[0], records[-1]
    require(environment.get('type') == 'environment' and complete.get('type') == 'complete',
            'missing environment or complete record')
    require(environment.get('mode') == complete.get('mode') == mode, 'wrong run mode')
    require(complete.get('count') == 14 and sorted(complete.get('ids', [])) == sorted(IDS),
            'wrong complete shortlist')
    expected_environment = dict(java_vendor='Eclipse Adoptium', java_version='17.0.17',
                                vm_version='17.0.17+10', jvm_arguments=FLAGS,
                                warmup_memory=100, warmup_storage=5, os='Linux')
    require(all(environment.get(k) == v for k, v in expected_environment.items()),
            'expected Linux Temurin 17.0.17+10, pinned flags and #839 warmups')
    require(environment.get('arch') in ('amd64', 'x86_64'), 'expected x86_64 JVM')
    build = environment.get('build', {})
    require(build.get('core_revision') == identity['coreSha'] and
            build.get('binding_revision') == identity['bindingSha'], 'wrong sample build identity')
    # Consume exactly one complete series per ID. Extra/missing/duplicate records,
    # reordered markers and mixed smoke/timing streams cannot become evidence.
    position = 1
    metrics = {}
    for name in IDS:
        means = []
        iterations = 1 if name in IDS[-2:] else 20
        if mode == 'benchmark':
            for sample in range(10):
                require(position < len(records) - 1, f'missing samples: {name}')
                row = records[position]
                position += 1
                require(row.get('type') == 'sample' and row.get('id') == name and
                        type(row.get('sample')) is int and row['sample'] == sample and
                        type(row.get('iterations')) is int and row['iterations'] == iterations,
                        f'wrong sample identity or iterations: {name}')
                total, mean = row.get('total_ns'), row.get('ns_per_op')
                require(type(total) is int and total > 0, f'invalid total_ns: {name}')
                require(type(mean) in (int, float) and math.isfinite(mean) and mean > 0 and
                        math.isclose(mean, total / iterations, rel_tol=1e-12), f'invalid ns_per_op: {name}')
                means.append(total / iterations)
            value = statistics.median(means)
            require(math.isfinite(value) and value > 0, f'invalid median: {name}')
            metrics[name] = {'latency': {'value': value}}
        require(position < len(records) - 1 and records[position] == dict(type='checked', id=name),
                f'missing or unexpected check: {name}')
        position += 1
    require(position == len(records) - 1, 'extra stream records')
    return metrics, dict(distribution='temurin', vendor=environment['java_vendor'],
                         version=environment['vm_version'], flags=environment['jvm_arguments'])


def sha(data):
    return hashlib.sha256(data).hexdigest()


def source_files(directory):
    return sorted(p for p in directory.rglob('*') if p.is_file() and
                  not IGNORED.intersection(p.relative_to(directory).parts))


def prepare(root):
    # Mirror NativeLoader's libc selection before retaining the glibc resource.
    require(sys.platform == 'linux' and os.uname().machine == 'x86_64' and
            not list(Path('/lib').glob('ld-musl-*')), 'nightly requires Linux x86_64 glibc')
    # Direct java invocation avoids launcher JAVA_OPTS/CLASSPATH and fixes the
    # complete classpath. Reject JVM injection and native search overrides too.
    for name in ('JAVA_OPTS', 'JDK_JAVA_OPTIONS', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS',
                 'CLASSPATH', 'JNA_LIBRARY_PATH', 'JNA_BOOT_LIBRARY_PATH', 'LD_PRELOAD', 'LD_LIBRARY_PATH'):
        require(not os.environ.get(name), f'{name} must be unset for a nightly run')
    java = Path(os.environ['JAVA_HOME']) / 'bin/java'
    require(java.is_file(), 'JAVA_HOME must point to the pinned JDK')
    identity = dict(coreSha=os.environ['BENCH_SHA'], workflowSha=os.environ['WORKFLOW_SHA'],
                    bindingSha=subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip())
    require(all(re.fullmatch('[a-f0-9]{40}', value) for value in identity.values()), 'expected full Git SHAs')
    manual = root / 'bench/manual'
    build = parse_json((manual / 'build/evidence/build.json').read_text())
    require(build['core_revision'] == identity['coreSha'] and build['binding_revision'] == identity['bindingSha'],
            'build identity differs from requested core or actual binding HEAD')
    metadata = parse_json((manual / 'build/evidence/cargo-metadata.json').read_text())
    cores = [p for p in metadata['packages'] if p['name'] == 'minigraf']
    require(len(cores) == 1 and cores[0]['source'] is None, 'core must be a single patched local dependency')
    core_dir = Path(cores[0]['manifest_path']).resolve().parent
    core_sha = subprocess.check_output(['git', '-C', str(core_dir), 'rev-parse', 'HEAD'], text=True).strip()
    require(core_sha == identity['coreSha'], 'resolved core checkout differs from BENCH_SHA')
    require(sha((root / 'Cargo.lock').read_bytes()) == build['cargo_lock_sha256'], 'Cargo.lock changed since build')
    digest = hashlib.sha256()
    for path in source_files(manual):
        digest.update(str(path.relative_to(manual)).encode() + b'\0' + path.read_bytes() + b'\0')
    require(digest.hexdigest() == build['harness_sha256'], 'manual harness changed since build; rebuild first')

    lib = manual / 'build/install/minigraf-jvm-benchmark/lib'
    binding_jar = lib / 'minigraf-jvm-0.0.0-local.jar'
    require(sha(binding_jar.read_bytes()) == build['binding_jar_sha256'], 'installed binding JAR differs from build')
    with zipfile.ZipFile(binding_jar) as jar:
        resource = 'natives/linux/x86_64/libminigraf_ffi.so'
        require(jar.namelist().count(resource) == 1, 'missing or duplicate packaged native')
        native = jar.read(resource)
    # NativeLoader extracts this resource and supplies an absolute libraryOverride
    # to UniFFI/JNA. Retain these packaged bytes, not just target/release's hash.
    require(sha(native) == build['native_sha256'] == sha((root / 'target/release/libminigraf_ffi.so').read_bytes()),
            'packaged native differs from measured build')
    harness_files = {str(p.relative_to(root)): sha(p.read_bytes()) for p in source_files(root / 'bench')}
    classpath = os.pathsep.join(str(p) for p in sorted(lib.glob('*.jar')))
    command = [str(java), *FLAGS, '-cp', classpath, 'io.github.project_minigraf.benchmark.BenchmarkKt']
    return identity, harness_files, native, command


def run(root, mode):
    output = root / mode
    for path in (output, root / 'binding-results.json', root / 'binding-provenance.json'):
        if os.path.lexists(path):
            raise FileExistsError(f'refusing stale output: {path}')
    identity, harness_files, native, command = prepare(root)
    # The harness itself creates the directory exclusively. In particular its
    # complete record precedes scratch deletion, so subprocess success is required.
    subprocess.run([*command, mode, str(output), str(root / 'bench/manual/build/evidence/build.json')],
                   cwd=root, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                   stderr=subprocess.STDOUT, text=True, check=True)
    raw = output / 'minigraf-bench.jsonl'
    records = [parse_json(line) for line in raw.read_text().splitlines()]
    metrics, runtime = validate(records, mode, identity)
    if mode == 'benchmark':
        provenance = dict(identity, artifactPath='benchmark/libminigraf_ffi.so', artifactSha256=sha(native),
                          harnessFiles=harness_files,
                          harnessSha256=sha(json.dumps(harness_files, sort_keys=True, separators=(',', ':')).encode()),
                          runtime=runtime)
        (output / 'libminigraf_ffi.so').write_bytes(native)
        raw.rename(output / 'samples.jsonl')
        # Exclusive writes: no stale result can be silently replaced. A failure
        # anywhere still exits nonzero and prints no accepted execution markers.
        for filename, value in [('binding-provenance.json', provenance), ('binding-results.json', metrics)]:
            with (root / filename).open('x') as destination:
                destination.write(json.dumps(value, sort_keys=True, indent=2, allow_nan=False) + '\n')
    for name in IDS:
        print(f'ok: {name}')


def main():
    require(sys.argv[1:] in ([], ['--test']), 'usage: bench/nightly.sh [--test]')
    root = Path(__file__).resolve().parent.parent
    require(Path.cwd().resolve() == root, 'run bench/nightly.sh from the binding root')
    run(root, 'smoke' if sys.argv[1:] else 'benchmark')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        if isinstance(error, subprocess.CalledProcessError) and error.output:
            print(error.output, file=sys.stderr)
        print(f'nightly: {error}', file=sys.stderr)
        sys.exit(1)
