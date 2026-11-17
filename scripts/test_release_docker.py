import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import call, patch

import release_docker as docker


class DockerReleaseTest(unittest.TestCase):
    repo = 'ghcr.io/owner/photographer/focusstack'
    revision = 'a' * 40
    digest = 'sha256:' + 'b' * 64

    def test_only_advance_the_newest_release_in_each_series(self):
        cases = [
            ('0.9.10', ['0.9.9', '0.9.10', 'latest'], ['0.9', '0', 'latest']),
            ('0.9.9', ['0.9.10'], []),
            ('0.9.10', ['0.10.0'], ['0.9']),
            ('0.9.10', ['1.0.0'], ['0.9', '0']),
        ]
        for version, tags, expected in cases:
            with self.subTest(version=version, tags=tags):
                self.assertEqual(list(docker.eligible_aliases(version, docker.versions(tags))), expected)

    def test_partial_release_repairs_only_the_missing_alias_from_the_digest(self):
        image = {'Digest': self.digest, 'Labels': {'org.opencontainers.image.revision': self.revision}}
        listing = subprocess.CompletedProcess([], 0, json.dumps({'Tags': ['1.2.3', '1', 'latest']}))
        with patch.object(docker.subprocess, 'run', return_value=listing) as commands, \
                patch.object(docker, 'inspect', return_value=image), \
                patch.dict(docker.os.environ, EXPECTED_DIGEST=self.digest):
            docker.run('promote', self.repo, '1.2.3', self.revision)
        copies = [c for c in commands.call_args_list if c.args[0][1] == 'copy']
        self.assertEqual(copies, [call(['skopeo', 'copy', '--all', '--preserve-digests',
                                        'docker://' + self.repo + '@' + self.digest,
                                        'docker://' + self.repo + ':1.2'], check=True)])

    def test_existing_version_must_match_its_source(self):
        listing = subprocess.CompletedProcess([], 0, '{"Tags":["1.2.3"]}')
        with patch.object(docker.subprocess, 'run', return_value=listing), \
                patch.object(docker, 'inspect', return_value={'Labels': {}}), \
                self.assertRaisesRegex(ValueError, 'different source commit'):
            docker.run('check', self.repo, '1.2.3', self.revision)

    def test_prepare_reuses_the_digest_of_an_existing_release(self):
        image = {'Digest': self.digest, 'Labels': {'org.opencontainers.image.revision': self.revision}}
        listing = subprocess.CompletedProcess([], 0, '{"Tags":["1.2.3"]}')
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / 'output'
            with patch.object(docker.subprocess, 'run', return_value=listing), \
                    patch.object(docker, 'inspect', return_value=image), \
                    patch.dict(docker.os.environ, GITHUB_OUTPUT=str(output)):
                docker.run('check', self.repo, '1.2.3', self.revision)
            self.assertEqual(output.read_text().splitlines(), ['exists=true', 'digest=' + self.digest])

    def test_registry_error_is_not_an_absent_image(self):
        denied = subprocess.CompletedProcess([], 1, '', 'unauthorized')
        with patch.object(docker.subprocess, 'run', return_value=denied), \
                self.assertRaisesRegex(RuntimeError, 'unauthorized'):
            docker.run('check', self.repo, '1.2.3', self.revision)

    def test_a_newer_main_image_retains_latest(self):
        listing = subprocess.CompletedProcess([], 0, '{"Tags":["1.2.3","1","1.2","latest"]}')
        ancestry = subprocess.CompletedProcess([], 1)
        image = {'Digest': self.digest, 'Labels': {'org.opencontainers.image.revision': self.revision}}
        main = {'Digest': 'different', 'Labels': {'org.opencontainers.image.revision': 'f' * 40}}
        with patch.object(docker.subprocess, 'run', side_effect=[listing, ancestry]) as commands, \
                patch.object(docker, 'inspect', side_effect=[image, image, image, main]), \
                patch.dict(docker.os.environ, EXPECTED_DIGEST=self.digest):
            docker.run('promote', self.repo, '1.2.3', self.revision)
        self.assertEqual(commands.call_args.args[0], ['git', 'merge-base', '--is-ancestor',
                                                       'f' * 40, self.revision])


if __name__ == '__main__':
    unittest.main()
