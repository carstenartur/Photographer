import io
from pathlib import Path
import tempfile
import unittest
from urllib.error import HTTPError

from verify_central_release import CENTRAL, PRIMARY_SUFFIXES, PUBLIC_ARTIFACTS, PUBLIC_GROUP_PATH, state


class PublicArtifacts(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.distribution = Path(temp.name)
        self.version = '0.1.6'
        self.public = {}
        for artifact in PUBLIC_ARTIFACTS:
            for suffix in PRIMARY_SUFFIXES:
                path = Path(PUBLIC_GROUP_PATH, artifact, self.version, artifact + '-' + self.version + suffix)
                target = self.distribution / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(str(path).encode())
                self.public[CENTRAL + path.as_posix()] = target.read_bytes()

    def fetch(self, url, timeout):
        if url not in self.public:
            raise HTTPError(url, 404, 'Not Found', {}, None)
        return io.BytesIO(self.public[url])

    def status(self):
        return state(self.distribution, self.version, self.fetch)

    def test_complete_partial_and_absent(self):
        self.assertEqual(self.status(), 'published')
        del self.public[next(iter(self.public))]
        self.assertEqual(self.status(), 'partial')
        self.public.clear()
        self.assertEqual(self.status(), 'absent')

    def test_immutable_content_conflict(self):
        self.public[next(iter(self.public))] = b'other release'
        with self.assertRaisesRegex(ValueError, 'differs'):
            self.status()

    def test_network_failure_is_not_absence(self):
        def failing_request(url, timeout):
            raise HTTPError(url, 503, 'Unavailable', {}, None)
        with self.assertRaises(HTTPError):
            state(self.distribution, self.version, failing_request)


if __name__ == '__main__':
    unittest.main()
