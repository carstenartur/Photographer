"""Regression tests for immutable Git-backed Maven releases."""
from contextlib import chdir, contextmanager, redirect_stderr, redirect_stdout
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from urllib.request import Request, urlopen
from urllib.error import HTTPError

import cr3_repository as repo
import run_maven_with_retry
from test_validate_cr3_central_bundle import valid_entries, VERSION


class RepositoryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.stage = self.root / 'stage'
        self.checkout = self.root / 'checkout'
        for name, data in valid_entries().items():
            if name.endswith(('.asc', '.sha256', '.sha512')):
                continue
            path = self.stage / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        self.checkout.mkdir()

    @contextmanager
    def consumer_maven(self, mode='valid'):
        """Keep the HTTP origin and helper real; replace Maven and the retry delay."""
        binary = self.root / 'bin'
        binary.mkdir(exist_ok=True)
        trace = self.root / 'consumer-calls.jsonl'
        trace.unlink(missing_ok=True)
        maven = binary / 'mvn'
        maven.write_text(f'#!{sys.executable}\n' + '''
import json
import os
from pathlib import Path
import sys
from urllib.request import urlopen

arguments = sys.argv[1:]
properties = dict(argument[2:].split('=', 1) for argument in arguments if argument.startswith('-D'))
cache = Path(properties['maven.repo.local'])
jars = Path(properties['outputDirectory'])
url = properties['photographer.repository.url']
trace = Path(os.environ['CONSUMER_TEST_TRACE'])
calls = trace.read_text().splitlines() if trace.exists() else []
with trace.open('a') as stream:
    stream.write(json.dumps({'arguments': arguments, 'cache': str(cache), 'url': url,
                             'cwd': os.getcwd()}) + '\\n')
assert arguments[-1] == 'org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy-dependencies'
assert '-Pphotographer-releases' in arguments
cache.mkdir(exist_ok=True)
marker = cache / 'retry-marker'
if not calls:
    # The first attempt reaches the live origin before Central rate limits a plugin.
    with urlopen(url + 'io/github/carstenartur/cr3-imageio/1.2.3/cr3-imageio-1.2.3.pom') as response:
        marker.write_bytes(response.read())
    print('[ERROR] Plugin org.apache.maven.plugins:maven-dependency-plugin:3.11.0 '
          'or one of its dependencies could not be resolved:', file=sys.stderr)
    print('[ERROR] The following artifacts could not be resolved: '
          'org.apache.maven.plugins:maven-dependency-plugin:pom:3.11.0 (absent): '
          'Could not transfer artifact org.apache.maven.plugins:maven-dependency-plugin:pom:3.11.0 '
          'from/to central (https://repo.maven.apache.org/maven2): HTTP Status: 429', file=sys.stderr)
    sys.exit(1)
assert marker.is_file(), 'Retry replaced the original isolated dependency cache'
assert '-U' in arguments, 'Retry did not force a recheck of missing dependencies'
jars.mkdir(exist_ok=True)
mode = os.environ['CONSUMER_TEST_MODE']
for artifact in ('cr3-container', 'cr3-metadata', 'cr3-codec', 'cr3-processing', 'cr3-core', 'cr3-imageio'):
    for suffix in ('.pom', '.jar'):
        name = artifact + '-1.2.3' + suffix
        relative = Path('io/github/carstenartur', artifact, '1.2.3', name)
        if mode == 'unserved':
            data = (Path(os.environ['CONSUMER_TEST_STAGE']) / relative).read_bytes()
        else:
            with urlopen(url + relative.as_posix()) as response:
                data = response.read()
        if mode == 'tampered' and artifact == 'cr3-imageio' and suffix == '.jar':
            data += b'tampered'
        destination = cache / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
        if suffix == '.jar' and not (mode == 'missing' and artifact == 'cr3-imageio'):
            (jars / name).write_bytes(data)
print('[INFO] BUILD SUCCESS')
''')
        maven.chmod(0o755)
        original_run = subprocess.run
        helper = str(Path(repo.__file__).with_name('run_maven_with_retry.py'))

        def launch(command, **kwargs):
            if command[:2] == [sys.executable, helper]:
                # Only bypass Python's outer launcher; helper/Popen/fake Maven stay real.
                with chdir(kwargs['cwd']), patch.object(run_maven_with_retry.time, 'sleep'):
                    status = run_maven_with_retry.main(command[2:])
                if kwargs.get('check') and status:
                    raise subprocess.CalledProcessError(status, command)
                return subprocess.CompletedProcess(command, status)
            return original_run(command, **kwargs)

        output = io.StringIO()
        with patch.dict(os.environ, {
            'PATH': str(binary) + os.pathsep + os.environ['PATH'],
            'CONSUMER_TEST_TRACE': str(trace),
            'CONSUMER_TEST_STAGE': str(self.stage),
            'CONSUMER_TEST_MODE': mode,
        }), patch.object(repo.subprocess, 'run', side_effect=launch), \
                redirect_stdout(output), redirect_stderr(output):
            yield trace, output

    def verify_test_consumer(self):
        try:
            repo.verify_consumer(self.root, self.stage, VERSION)
        except subprocess.CalledProcessError as error:
            self.fail(f'Consumer failed before package validation: {error}')

    def test_consumer_recovers_from_429_in_the_same_isolated_repository(self):
        # release_handoff also passes a separate release checkout without these scripts.
        with self.consumer_maven() as (trace, output):
            self.verify_test_consumer()
        calls = [json.loads(line) for line in trace.read_text().splitlines()]
        self.assertEqual(2, len(calls))
        self.assertEqual(calls[0]['cache'], calls[1]['cache'])
        self.assertEqual(calls[0]['url'], calls[1]['url'])
        self.assertEqual([str(self.root), str(self.root)], [call['cwd'] for call in calls])
        self.assertNotIn('-U', calls[0]['arguments'])
        self.assertIn('-U', calls[1]['arguments'])
        self.assertIn('HTTP Status: 429', output.getvalue())
        self.assertIn('BUILD SUCCESS', output.getvalue())

    def test_recovered_consumer_still_rejects_changed_unserved_or_missing_artifacts(self):
        for mode, message in (
            ('tampered', 'consumer resolved different bytes'),
            ('unserved', 'consumer did not resolve .* from the staged repository'),
            ('missing', 'missing transitive dependency cr3-imageio'),
        ):
            with self.subTest(mode=mode), self.consumer_maven(mode) as (trace, _):
                with self.assertRaisesRegex(ValueError, message):
                    self.verify_test_consumer()
                self.assertEqual(2, len(trace.read_text().splitlines()))

    def test_immutable_retries_and_metadata_across_releases(self):
        older = self.checkout / repo.PUBLIC_GROUP_PATH / 'cr3-imageio' / '1.0.0'
        older.mkdir(parents=True)
        (older / 'keep.jar').write_bytes(b'older release')
        repo.merge_release(self.stage, self.checkout, VERSION)
        metadata = older.parent / 'maven-metadata.xml'
        self.assertIn('<version>1.0.0</version>', metadata.read_text())
        self.assertIn('<release>1.2.3</release>', metadata.read_text())
        self.assertEqual(metadata.with_suffix('.xml.sha256').read_text().strip(),
                         hashlib.sha256(metadata.read_bytes()).hexdigest())
        first = {p: p.read_bytes() for p in self.checkout.rglob('*') if p.is_file()}
        repo.merge_release(self.stage, self.checkout, VERSION)
        self.assertEqual(first, {p: p.read_bytes() for p in self.checkout.rglob('*') if p.is_file()})
        next(self.checkout.rglob('cr3-imageio-1.2.3.jar')).write_bytes(b'conflict')
        with self.assertRaisesRegex(ValueError, 'immutable'):
            repo.merge_release(self.stage, self.checkout, VERSION)

    def test_repository_origin_records_only_successful_payload_downloads(self):
        pom = next(self.stage.rglob('*.pom'))
        relative = pom.relative_to(self.stage).as_posix()
        with repo.serve_stage(self.stage) as (url, served):
            with urlopen(Request(url + relative, method='HEAD')):
                pass
            with self.assertRaises(HTTPError):
                urlopen(url + 'missing.jar')
            self.assertEqual(set(), served)
            with urlopen(url + relative) as response:
                self.assertEqual(pom.read_bytes(), response.read())
        self.assertEqual({relative}, served)

    def test_partial_or_extra_artifacts_are_rejected(self):
        (self.stage / 'unrelated.jar').write_bytes(b'other')
        with self.assertRaisesRegex(ValueError, 'unexpected'):
            repo.validate_stage(self.stage, VERSION)
        (self.stage / 'unrelated.jar').unlink()
        next(self.stage.rglob('*.jar')).unlink()
        with self.assertRaisesRegex(ValueError, 'missing'):
            repo.validate_stage(self.stage, VERSION)

    def test_git_transport_starts_an_independent_branch_and_retries_identically(self):
        origin = self.root / 'origin.git'
        subprocess.run(['git', 'init', '-q', '--bare', str(origin)], check=True)
        git = self.root / 'source'
        subprocess.run(['git', 'init', '-q', str(git)], check=True)
        subprocess.run(['git', 'remote', 'add', 'origin', str(origin)], cwd=git, check=True)
        repo.publish(git, self.stage, VERSION, lambda: None)
        tip = subprocess.check_output(['git', 'rev-parse', repo.BRANCH], cwd=origin)
        repo.publish(git, self.stage, VERSION, lambda: None)
        self.assertEqual(tip, subprocess.check_output(['git', 'rev-parse', repo.BRANCH], cwd=origin))
        self.assertEqual(b'1', subprocess.check_output(['git', 'rev-list', '--count', repo.BRANCH], cwd=origin).strip())
        def abort():
            raise ValueError('main moved')
        with self.assertRaisesRegex(ValueError, 'main moved'):
            repo.publish(git, self.stage, VERSION, abort)


if __name__ == '__main__':
    unittest.main()
