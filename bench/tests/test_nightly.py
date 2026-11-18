"""Adapter contract tests; synthetic times test arithmetic, never performance."""
import contextlib
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location('nightly', Path(__file__).parents[1] / 'nightly.py')
nightly = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(nightly)

SUFFIXES = [
    'prepared_execute/1k', 'cursor_batch_1/1k', 'cursor_batch_256/1k',
    'set_roundtrip/2', 'predicate/1k', 'aggregate/1k', 'prepare/point',
    'register_predicate/first', 'register_aggregate/first', 'fact_log_batch_256/1k',
    'options_error/negative_cache', 'options_readonly_open/1k', 'file_transact/1k',
    'log_writer_append_finish/1k',
]
IDS = ['binding/jvm/' + name for name in SUFFIXES]
FLAGS = ['-Xms512m', '-Xmx512m', '-XX:+UseG1GC']
IDENTITY = dict(coreSha='a' * 40, bindingSha='b' * 40, workflowSha='c' * 40)


def stream(mode='benchmark'):
    records = [dict(type='environment', mode=mode, java_vendor='Eclipse Adoptium',
                    java_version='17.0.17', vm_version='17.0.17+10', jvm_arguments=FLAGS[:],
                    os='Linux', arch='amd64', warmup_memory=100, warmup_storage=5,
                    build=dict(core_revision=IDENTITY['coreSha'], binding_revision=IDENTITY['bindingSha']))]
    for name in IDS:
        if mode == 'benchmark':
            iterations = 1 if name in IDS[-2:] else 20
            # Unsorted means, fractional ns for the short operations.
            for sample, total in enumerate([201, 21, 181, 41, 161, 61, 141, 81, 121, 101]):
                records.append(dict(type='sample', id=name, sample=sample, iterations=iterations,
                                    total_ns=total, ns_per_op=total / iterations))
        records.append(dict(type='checked', id=name))
    records.append(dict(type='complete', mode=mode, count=14, ids=IDS[:]))
    return records


class NightlyTest(unittest.TestCase):
    def test_median_sample_means_stay_in_nanoseconds_and_exact_output_schema(self):
        metrics, runtime = nightly.validate(stream(), 'benchmark', IDENTITY)
        expected = {name: {'latency': {'value': 111.0 if name in IDS[-2:] else 5.55}} for name in IDS}
        self.assertEqual(expected, json.loads(json.dumps(metrics, allow_nan=False)))
        self.assertEqual(dict(distribution='temurin', vendor='Eclipse Adoptium',
                              version='17.0.17+10', flags=FLAGS), runtime)

    def test_smoke_has_checks_but_no_metrics(self):
        self.assertEqual({}, nightly.validate(stream('smoke'), 'smoke', IDENTITY)[0])
        with self.assertRaises(ValueError):
            nightly.validate(stream(), 'smoke', IDENTITY)

    def test_rejects_incomplete_duplicate_extra_or_wrong_samples(self):
        original = stream()
        variants = []
        variants.append(original[:-1])
        variants.append(original[:1] + original)
        variants.append(original + [original[-2]])
        variants.append(original[:2] + original[1:])
        variants.append([r for r in original if r.get('id') != IDS[0]])
        variants.append(original + [dict(type='unknown')])
        for field, value in [('id', 'binding/jvm/extra'), ('sample', 9), ('iterations', 1),
                             ('total_ns', 0), ('total_ns', 201.0), ('total_ns', True),
                             ('ns_per_op', 10), ('ns_per_op', float('nan')),
                             ('ns_per_op', float('inf')), ('ns_per_op', -1)]:
            bad = copy.deepcopy(original)
            bad[1][field] = value
            variants.append(bad)
        bad = copy.deepcopy(original)
        bad[-1]['ids'][-1] = IDS[0]
        variants.append(bad)
        for bad in variants:
            with self.subTest(bad=bad[1]), self.assertRaises(ValueError):
                nightly.validate(bad, 'benchmark', IDENTITY)

    def test_rejects_runtime_and_identity_drift(self):
        for field, value in [('java_vendor', 'Oracle'), ('java_version', '17.0.18'),
                             ('vm_version', '17.0.17+9'), ('jvm_arguments', FLAGS + ['-Dfoo=bar']),
                             ('warmup_memory', 3), ('warmup_storage', 3),
                             ('build', dict(core_revision='d' * 40, binding_revision='b' * 40))]:
            records = stream()
            records[0][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                nightly.validate(records, 'benchmark', IDENTITY)

    def test_duplicate_json_keys_and_nonfinite_constants_rejected(self):
        for raw in ['{"id":1,"id":2}', '{"id":1,"\\u0069d":2}', '{"n":NaN}', '{"n":Infinity}']:
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                nightly.parse_json(raw)

    def test_failed_process_even_with_complete_record_never_publishes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            def failed(*args, **kwargs):
                out = root / 'benchmark'
                out.mkdir()
                (out / 'minigraf-bench.jsonl').write_text('\n'.join(map(json.dumps, stream())) + '\n')
                raise subprocess.CalledProcessError(1, args[0])
            with patch.object(nightly, 'prepare', return_value=(IDENTITY, {}, b'native', ['java'])), \
                    patch.object(nightly.subprocess, 'run', side_effect=failed), \
                    contextlib.redirect_stdout(io.StringIO()) as output:
                with self.assertRaises(subprocess.CalledProcessError):
                    nightly.run(root, 'benchmark')
            self.assertEqual('', output.getvalue())
            self.assertFalse((root / 'binding-results.json').exists())
            self.assertFalse((root / 'binding-provenance.json').exists())
            self.assertFalse((root / 'benchmark/samples.jsonl').exists())

    def test_stale_output_is_rejected_before_launch(self):
        for name in ['benchmark', 'binding-results.json', 'binding-provenance.json']:
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                (root / name).touch()
                with patch.object(nightly, 'prepare') as prepare, self.assertRaises(FileExistsError):
                    nightly.run(root, 'benchmark')
                prepare.assert_not_called()

    def test_publish_only_after_success_and_smoke_never_publishes(self):
        for mode in ['benchmark', 'smoke']:
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                def succeeded(*args, **kwargs):
                    out = root / mode
                    out.mkdir()
                    (out / 'minigraf-bench.jsonl').write_text('\n'.join(map(json.dumps, stream(mode))) + '\n')
                with patch.object(nightly, 'prepare', return_value=(IDENTITY, {'bench/nightly.sh': 'd' * 64}, b'native', ['java'])), \
                        patch.object(nightly.subprocess, 'run', side_effect=succeeded), \
                        contextlib.redirect_stdout(io.StringIO()) as output:
                    nightly.run(root, mode)
                self.assertEqual(['ok: ' + name for name in IDS], output.getvalue().splitlines())
                self.assertEqual(mode == 'benchmark', (root / 'binding-results.json').exists())
                self.assertEqual(mode == 'benchmark', (root / 'binding-provenance.json').exists())
                if mode == 'benchmark':
                    self.assertEqual(b'native', (root / 'benchmark/libminigraf_ffi.so').read_bytes())
                    self.assertTrue((root / 'benchmark/samples.jsonl').is_file())
                    provenance = json.loads((root / 'binding-provenance.json').read_text())
                    self.assertEqual('benchmark/libminigraf_ffi.so', provenance['artifactPath'])
                    self.assertEqual(IDENTITY['coreSha'], provenance['coreSha'])
                    canonical = json.dumps(provenance['harnessFiles'], sort_keys=True, separators=(',', ':'))
                    self.assertEqual(hashlib.sha256(canonical.encode()).hexdigest(), provenance['harnessSha256'])
                    self.assertEqual(hashlib.sha256(b'native').hexdigest(), provenance['artifactSha256'])

    def test_successful_exit_with_invalid_results_does_not_publish(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            def succeeded(*args, **kwargs):
                (root / 'benchmark').mkdir()
                # Plausible complete record, but one expected check is missing.
                records = stream()
                del records[-2]
                (root / 'benchmark/minigraf-bench.jsonl').write_text('\n'.join(map(json.dumps, records)))
            with patch.object(nightly, 'prepare', return_value=(IDENTITY, {}, b'native', ['java'])), \
                    patch.object(nightly.subprocess, 'run', side_effect=succeeded), \
                    contextlib.redirect_stdout(io.StringIO()) as output:
                with self.assertRaises(ValueError):
                    nightly.run(root, 'benchmark')
            self.assertEqual('', output.getvalue())
            self.assertFalse((root / 'binding-results.json').exists())
            self.assertFalse((root / 'binding-provenance.json').exists())


class BuildEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.manual = self.root / 'bench/manual'
        self.evidence = self.manual / 'build/evidence'
        self.evidence.mkdir(parents=True)
        (self.manual / 'build.sh').write_text('manual builder')
        (self.root / 'bench/nightly.sh').write_text('nightly launcher')
        (self.root / 'Cargo.lock').write_text('lock')
        (self.root / 'jdk/bin').mkdir(parents=True)
        (self.root / 'jdk/bin/java').touch()
        (self.root / 'target/release').mkdir(parents=True)
        (self.root / 'target/release/libminigraf_ffi.so').write_bytes(b'native')
        self.jar = self.manual / 'build/install/minigraf-jvm-benchmark/lib/minigraf-jvm-0.0.0-local.jar'
        self.jar.parent.mkdir(parents=True)
        self.write_jar(b'native')
        self.build = dict(core_revision=IDENTITY['coreSha'], binding_revision=IDENTITY['bindingSha'],
                          cargo_lock_sha256=hashlib.sha256(b'lock').hexdigest(),
                          harness_sha256=hashlib.sha256(b'build.sh\0manual builder\0').hexdigest(),
                          binding_jar_sha256=hashlib.sha256(self.jar.read_bytes()).hexdigest(),
                          native_sha256=hashlib.sha256(b'native').hexdigest())
        self.write_build()
        self.metadata = dict(packages=[dict(name='minigraf', source=None,
                                            manifest_path=str(self.root / 'core/Cargo.toml'))])
        self.write_metadata()
        self.environment = patch.dict(nightly.os.environ, dict(JAVA_HOME=str(self.root / 'jdk'),
                                      BENCH_SHA=IDENTITY['coreSha'], WORKFLOW_SHA=IDENTITY['workflowSha']), clear=True)
        self.environment.start()
        self.addCleanup(self.environment.stop)
        git_patch = patch.object(nightly.subprocess, 'check_output', side_effect=self.git_sha)
        self.git = git_patch.start()
        self.addCleanup(git_patch.stop)

    def git_sha(self, command, **kwargs):
        return IDENTITY['bindingSha' if command[2] == str(self.root) else 'coreSha'] + '\n'

    def write_jar(self, native):
        with zipfile.ZipFile(self.jar, 'w') as jar:
            jar.writestr('natives/linux/x86_64/libminigraf_ffi.so', native)

    def write_build(self):
        (self.evidence / 'build.json').write_text(json.dumps(self.build))

    def write_metadata(self):
        (self.evidence / 'cargo-metadata.json').write_text(json.dumps(self.metadata))

    def test_packaged_native_and_source_hashes_and_fixed_jvm_command(self):
        identity, files, native, command = nightly.prepare(self.root)
        self.assertEqual(IDENTITY, identity)
        self.assertEqual(b'native', native)
        self.assertEqual({'bench/manual/build.sh', 'bench/nightly.sh'}, set(files))
        for name, digest in files.items():
            self.assertEqual(hashlib.sha256((self.root / name).read_bytes()).hexdigest(), digest)
        self.assertEqual(FLAGS, command[1:4])
        self.assertEqual(str(self.root / 'jdk/bin/java'), command[0])
        self.assertEqual(str(self.jar), command[5])

    def test_rejects_different_packaged_native_even_when_jar_hash_matches(self):
        self.write_jar(b'wrong but plausible library')
        self.build['binding_jar_sha256'] = hashlib.sha256(self.jar.read_bytes()).hexdigest()
        self.write_build()
        with self.assertRaisesRegex(ValueError, 'packaged native'):
            nightly.prepare(self.root)

    def test_rejects_unpatched_core(self):
        self.metadata['packages'][0]['source'] = 'git+https://example.com/release-pin'
        self.write_metadata()
        with self.assertRaisesRegex(ValueError, 'patched local dependency'):
            nightly.prepare(self.root)

    def test_rejects_supplied_label_for_different_core_checkout(self):
        self.git.side_effect = [IDENTITY['bindingSha'], 'd' * 40]
        with self.assertRaisesRegex(ValueError, 'resolved core checkout'):
            nightly.prepare(self.root)

    def test_rejects_stale_harness(self):
        (self.manual / 'build.sh').write_text('changed after build')
        with self.assertRaisesRegex(ValueError, 'harness changed'):
            nightly.prepare(self.root)

    def test_rejects_jvm_or_library_override(self):
        for variable in ['JAVA_OPTS', 'JDK_JAVA_OPTIONS', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS',
                         'CLASSPATH', 'JNA_LIBRARY_PATH', 'JNA_BOOT_LIBRARY_PATH', 'LD_PRELOAD', 'LD_LIBRARY_PATH']:
            with self.subTest(variable=variable), patch.dict(nightly.os.environ, {variable: 'override'}), \
                    self.assertRaisesRegex(ValueError, variable):
                nightly.prepare(self.root)

    def test_rejects_musl_which_would_select_a_different_packaged_native(self):
        original = Path.glob
        def paths(path, pattern, **kwargs):
            if path == Path('/lib') and pattern == 'ld-musl-*':
                return iter([Path('/lib/ld-musl-x86_64.so.1')])
            return original(path, pattern, **kwargs)
        with patch.object(Path, 'glob', paths), self.assertRaisesRegex(ValueError, 'glibc'):
            nightly.prepare(self.root)


if __name__ == '__main__':
    unittest.main()
