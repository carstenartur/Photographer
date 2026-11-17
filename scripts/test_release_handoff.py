#!/usr/bin/env python3
"""Exercise a Maven version transaction in a local Git repository."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import release_handoff as release


class VersionTests(unittest.TestCase):
    def test_default_and_optional_target_versions(self):
        for target, successor, expected in (
            ('', '', ('0.1.6', '0.1.7-SNAPSHOT')),
            ('1.0.0', '', ('1.0.0', '1.0.1-SNAPSHOT')),
            ('', '0.2.0-SNAPSHOT', ('0.1.6', '0.2.0-SNAPSHOT')),
            ('1.0.0', '2.0.0-SNAPSHOT', ('1.0.0', '2.0.0-SNAPSHOT')),
        ):
            with self.subTest(target=target, successor=successor):
                self.assertEqual(release.resolve_versions('0.1.6-SNAPSHOT', target, successor), expected)

    def test_malformed_and_non_advancing_versions(self):
        for current, target, successor in (
            ('0.1.6', '', ''), ('0.1.6-SNAPSHOT', '0.1.7-SNAPSHOT', ''),
            ('0.1.6-SNAPSHOT', 'v1.2.3', ''), ('0.1.6-SNAPSHOT', '', '0.1.7'),
            ('0.1.6-SNAPSHOT', '1.2.0', '1.2.0-SNAPSHOT'),
            ('0.1.6-SNAPSHOT', '1.2.0', '1.1.99-SNAPSHOT'),
        ):
            with self.subTest(current=current, target=target, successor=successor):
                with self.assertRaises(ValueError):
                    release.resolve_versions(current, target, successor)


class HistoryTests(unittest.TestCase):
    current = '0.1.6-SNAPSHOT'
    target = '1.0.0'
    successor = '1.1.0-SNAPSHOT'

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.git('init', '-q', '-b', 'main')
        self.git('config', 'user.email', 'release@example.invalid')
        self.git('config', 'user.name', 'Release test')
        (self.root / 'README.md').write_text('Product sources\n')
        self.write_pom(self.current)
        self.source = self.commit('Checked source')
        self.git('switch', '-q', '-c', 'release-test')

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root, text=True).strip()

    def commit(self, message):
        self.git('add', '.')
        self.git('commit', '-qm', message)
        return self.git('rev-parse', 'HEAD')

    def write_pom(self, version, revision=None):
        (self.root / 'pom.xml').write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
            '<groupId>org.example</groupId><artifactId>product</artifactId>'
            f'<version>{version}</version><properties><revision>{revision or version}</revision>'
            '</properties></project>\n')

    def prepare(self, extra_release_file=False, wrong_revision=False, extra_snapshot_file=False):
        self.write_pom(self.target, self.current if wrong_revision else self.target)
        if extra_release_file:
            (self.root / 'README.md').write_text('Unreviewed changes at release\n')
        stable = self.commit('Prepare release')
        self.tag = 'v' + self.target
        self.git('tag', '-a', self.tag, '-m', 'Release', stable)
        self.write_pom(self.successor)
        if extra_snapshot_file:
            (self.root / 'README.md').write_text('Surprise snapshot change\n')
        self.next_commit = self.commit('Prepare next development version')

    def validate(self):
        return release.verify_history(self.root, self.source, self.tag, self.next_commit,
                                      self.current, self.target, self.successor)

    def test_checked_source_tag_and_next_snapshot(self):
        self.prepare()
        self.validate()
        self.assertEqual(self.git('rev-parse', 'main'), self.source)
        self.assertEqual(self.git('diff', '--name-only', 'main', 'HEAD'), 'pom.xml')

    def test_unreviewed_release_changes_are_rejected(self):
        self.prepare(extra_release_file=True)
        with self.assertRaises(ValueError):
            self.validate()

    def test_snapshot_cannot_introduce_unrelated_changes(self):
        self.prepare(extra_snapshot_file=True)
        with self.assertRaises(ValueError):
            self.validate()

    def test_stable_version_and_cr3_revision_must_match(self):
        self.prepare(wrong_revision=True)
        with self.assertRaises(ValueError):
            self.validate()

    def test_tag_from_different_base_is_rejected(self):
        self.prepare()
        self.source = self.next_commit
        with self.assertRaises(ValueError):
            self.validate()


class PublicationTests(unittest.TestCase):
    tag = 'v0.1.6'
    digest = 'a' * 64
    jar = Path('focusstack.jar')

    def asset(self, digest):
        return {'assets': [{'name': self.jar.name, 'state': 'uploaded', 'digest': 'sha256:' + digest}]}

    def test_retry_reuses_matching_app_asset(self):
        with patch.object(release, 'release_record', return_value=self.asset(self.digest)), patch.object(release, 'gh') as gh:
            release.publish_app('owner/repo', self.tag, self.jar, self.digest)
            gh.assert_not_called()

    def test_retry_rejects_a_conflicting_app_asset(self):
        with patch.object(release, 'release_record', return_value=self.asset('b' * 64)), patch.object(release, 'gh') as gh:
            with self.assertRaises(ValueError):
                release.publish_app('owner/repo', self.tag, self.jar, self.digest)
            gh.assert_not_called()

    def test_retry_completes_an_interrupted_app_upload(self):
        records = [{'assets': []}, self.asset(self.digest)]
        with patch.object(release, 'release_record', side_effect=records), patch.object(release, 'gh') as gh:
            release.publish_app('owner/repo', self.tag, self.jar, self.digest)
            self.assertEqual(gh.call_count, 1)
            self.assertEqual(gh.call_args.args[:2], ('release', 'upload'))

    def test_existing_snapshot_pr_is_reused(self):
        data = '[{"url":"https://example.test/pull/12","state":"OPEN","headRepositoryOwner":{"login":"owner"},"headRepository":{"name":"repo"}}]'
        with patch.object(release, 'gh', return_value=data) as gh:
            url = release.snapshot_pr('owner/repo', 'release/next', '0.1.6', '0.1.7-SNAPSHOT')
            self.assertEqual(url, 'https://example.test/pull/12')
            self.assertEqual(gh.call_count, 1)


if __name__ == '__main__':
    unittest.main()
