"""Regression tests for immutable Git-backed Maven releases."""
import hashlib
from pathlib import Path
import subprocess
import tempfile
import unittest
from urllib.request import Request, urlopen
from urllib.error import HTTPError

import cr3_repository as repo
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
