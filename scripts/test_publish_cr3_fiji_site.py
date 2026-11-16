import gzip
import json
import subprocess
import io
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import shutil
from threading import Thread
from pathlib import Path
import tempfile
from unittest.mock import patch
import unittest
import xml.etree.ElementTree as ET
from zipfile import ZipFile

import build_cr3_fiji_site as builder
from publish_cr3_fiji_site import ARTIFACTS, attach_archives, install, previous_archive, verify_served


def archive(root, version, timestamp):
    temp = io.BytesIO()
    with ZipFile(temp, 'w') as zipped:
        db = ET.Element('pluginRecords')
        for filename, coordinate in ARTIFACTS.items():
            payload = io.BytesIO()
            with ZipFile(payload, 'w') as jar:
                jar.writestr(f'META-INF/maven/io.github.carstenartur/{coordinate}/pom.properties',
                             f'version={version}\ngroupId=io.github.carstenartur\nartifactId={coordinate}\n')
            jar = payload.getvalue()
            plugin = ET.SubElement(db, 'plugin', filename=filename)
            ET.SubElement(plugin, 'version', timestamp=timestamp, filesize=str(len(jar)), checksum='a' * 40)
            zipped.writestr(filename + '-' + timestamp, jar)
        zipped.writestr('db.xml.gz', gzip.compress(ET.tostring(db), mtime=0))
    path = root / (timestamp + '.zip')
    path.write_bytes(temp.getvalue())
    return path


class BuildInputTest(unittest.TestCase):
    def test_rejects_missing_or_different_license_before_accessing_fiji(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            module = root / 'cr3-imagej'
            libraries = root / 'libraries'
            output = root / 'target' / 'site'
            version = '0.1.5'
            license_notice = (builder.ROOT / 'LICENSE').read_bytes()
            plugin = module / 'target' / f'cr3-imagej-{version}.jar'
            library_jars = [libraries / f'cr3-{name}-{version}.jar' for name in builder.LIBRARIES]
            for artifact in [*library_jars, plugin]:
                artifact.parent.mkdir(parents=True, exist_ok=True)
                with ZipFile(artifact, 'w') as jar:
                    jar.writestr('META-INF/cr3/LICENSE', license_notice)

            with patch.object(builder, 'MODULE', module):
                for artifact, notice in [(library_jars[0], None), (plugin, b'wrong notice')]:
                    with self.subTest(artifact=artifact.name):
                        with ZipFile(artifact, 'w') as jar:
                            if notice is not None:
                                jar.writestr('META-INF/cr3/LICENSE', notice)
                        with self.assertRaisesRegex(ValueError, 'META-INF/cr3/LICENSE') as error:
                            builder.build(root / 'fiji', version, libraries, output, None)
                        self.assertIn(artifact.name, str(error.exception))
                        self.assertFalse(output.parent.exists())
                        with ZipFile(artifact, 'w') as jar:
                            jar.writestr('META-INF/cr3/LICENSE', license_notice)


class PublicationTest(unittest.TestCase):
    def test_replaying_exact_release_preserves_other_pages(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'index.html').write_text('existing reports')
            asset = archive(root, '0.1.5', '20260101000000')
            install(asset, 'v0.1.5', root)
            expected = (root / 'fiji/cr3/db.xml.gz').read_bytes()
            install(asset, 'v0.1.5', root)
            self.assertEqual(expected, (root / 'fiji/cr3/db.xml.gz').read_bytes())
            self.assertEqual('existing reports', (root / 'index.html').read_text())
            self.assertEqual('0.1.5', (root / 'fiji/cr3/version.txt').read_text().strip())

    def test_snapshot_archive_is_rejected_before_any_publication(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with self.assertRaisesRegex(ValueError, '0.1.5'):
                install(archive(root, '0.1.5-SNAPSHOT', '20260101000000'), 'v0.1.5', root)
            self.assertFalse((root / 'fiji').exists())

    def test_release_rejects_a_fork_or_overwriting_an_existing_version(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            install(archive(root, '0.1.5', '20260101000000'), 'v0.1.5', root)
            with self.assertRaisesRegex(ValueError, 'immutable'):
                install(archive(root, '0.1.5', '20260102000000'), 'v0.1.5', root)
            with self.assertRaisesRegex(ValueError, 'history'):
                install(archive(root, '0.1.6', '20260103000000'), 'v0.1.6', root)
            self.assertEqual('0.1.5', (root / 'fiji/cr3/version.txt').read_text().strip())

    def test_first_stable_release_extends_the_live_preview(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            site = root / 'fiji' / 'cr3'
            preview = archive(root, '0.1.5-SNAPSHOT', '20260101000000')
            with ZipFile(preview) as z:
                z.extractall(site)
            (site / 'version.txt').write_text('0.1.5-SNAPSHOT\n')
            (site / 'index.html').write_text('Subscribe here')
            (site / 'LICENSE.txt').write_text('Project license')
            previous = root / 'previous.zip'
            previous_archive(site, previous)
            with ZipFile(previous) as old:
                self.assertNotIn('index.html', old.namelist())
                self.assertNotIn('version.txt', old.namelist())
                self.assertNotIn('LICENSE.txt', old.namelist())
                fresh = archive(root, '0.1.5', '20260201000000')
                stable = root / 'release.zip'
                with ZipFile(fresh) as current, ZipFile(stable, 'w') as z:
                    for source in (old, current):
                        for name in source.namelist():
                            if name != 'db.xml.gz':
                                z.writestr(name, source.read(name))
                    db = ET.fromstring(gzip.decompress(current.read('db.xml.gz')))
                    for plugin in db.findall('plugin'):
                        ET.SubElement(plugin, 'previous-version', checksum='a' * 40, timestamp='20260101000000')
                    z.writestr('db.xml.gz', gzip.compress(ET.tostring(db), mtime=0))
            install(stable, 'v0.1.5', root)
            self.assertEqual('0.1.5', (site / 'version.txt').read_text().strip())
            self.assertEqual('Subscribe here', (site / 'index.html').read_text())
            self.assertEqual('Project license', (site / 'LICENSE.txt').read_text())
            self.assertEqual(14, len(list(site.glob('*/*.jar-*'))))
            install(stable, 'v0.1.5', root)

    def test_rejects_an_unexpected_archive_entry(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            bad = archive(root, '0.1.5', '20260101000000')
            with ZipFile(bad, 'a') as z:
                z.writestr('../index.html', 'foreign file')
            with self.assertRaisesRegex(ValueError, 'Unexpected'):
                install(bad, 'v0.1.5', root)
            self.assertFalse((root / 'index.html').exists())

    def test_public_verification_requires_all_nine_anonymous_downloads_to_match(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            asset = archive(root, '0.1.5', '20260101000000')
            install(asset, 'v0.1.5', root)
            site = root / 'fiji/cr3'
            published = root / 'public'
            shutil.copytree(site, published)
            requests = []

            class Handler(SimpleHTTPRequestHandler):
                def log_message(self, *args):
                    pass

                def do_GET(self):
                    requests.append((self.path.lstrip('/'), self.headers.get('Authorization')))
                    super().do_GET()

            with ThreadingHTTPServer(('127.0.0.1', 0), partial(Handler, directory=published)) as server:
                thread = Thread(target=server.serve_forever)
                thread.start()
                url = f'http://127.0.0.1:{server.server_port}/'
                try:
                    verify_served(site, url, 'v0.1.5')
                    expected = {p.relative_to(site).as_posix() for p in site.rglob('*') if p.is_file()}
                    self.assertEqual(9, len(expected))
                    self.assertEqual({(name, None) for name in expected}, set(requests))
                    jar = next((published / 'jars').iterdir())
                    original = jar.read_bytes()
                    jar.write_bytes(original[:-1] + bytes([original[-1] ^ 1]))
                    with self.assertRaisesRegex(ValueError, jar.name):
                        verify_served(site, url, 'v0.1.5')
                    jar.write_bytes(original)
                    (published / 'version.txt').write_text('0.1.5-SNAPSHOT\n')
                    with self.assertRaisesRegex(ValueError, 'version.txt'):
                        verify_served(site, url, 'v0.1.5')
                finally:
                    server.shutdown()
                    thread.join()

    def test_partial_release_reuses_original_artifact_and_never_replaces_existing_zips(self):
        with tempfile.TemporaryDirectory() as temp:
            local = Path(temp)
            names = ['cr3-fiji-install.zip', 'cr3-fiji-update-site.zip']
            for name in names:
                (local / name).write_bytes(name.encode())
            # The previous job successfully uploaded only the installation archive.
            assets = {names[0]: (3, names[0].encode())}
            uploads = []

            def gh(args, *, check, stdout=None, **kwargs):
                if 'releases/tags/' in args[2]:
                    data = json.dumps({'id': 42, 'tag_name': 'v0.1.5', 'draft': False, 'prerelease': False})
                elif args[1] == 'api' and args[2].endswith('/releases/42/assets'):
                    data = json.dumps([[{'name': n, 'id': i, 'state': 'uploaded'} for n, (i, _) in assets.items()]])
                elif args[1] == 'api':
                    asset_id = int(args[2].split('/')[-1])
                    stdout.write(next(b for i, b in assets.values() if i == asset_id))
                    stdout.flush()
                    data = ''
                else:
                    self.assertEqual(['release', 'upload', 'v0.1.5'], args[1:4])
                    path = Path(args[4])
                    self.assertNotIn(path.name, assets)
                    self.assertNotIn('--clobber', args)
                    assets[path.name] = (4, path.read_bytes())
                    uploads.append(path.name)
                    data = ''
                return subprocess.CompletedProcess(args, 0, data.encode())

            with patch('publish_cr3_fiji_site.subprocess.run', side_effect=gh):
                attach_archives('owner/repo', 'v0.1.5', local)
                attach_archives('owner/repo', 'v0.1.5', local)
                self.assertEqual([names[1]], uploads)
                # A different existing archive prevents any new upload, in either order.
                del assets[names[0]]
                assets[names[1]] = (4, b'other verified build')
                with self.assertRaisesRegex(ValueError, 'byte'):
                    attach_archives('owner/repo', 'v0.1.5', local)
                self.assertEqual([names[1]], uploads)

    def test_release_asset_inventory_error_does_not_trigger_upload(self):
        calls = []
        def gh(args, **kwargs):
            calls.append(args)
            if 'releases/tags/' in args[2]:
                return subprocess.CompletedProcess(args, 0, b'{"id": 42, "tag_name": "v0.1.5", "draft": false, "prerelease": false}')
            raise subprocess.CalledProcessError(1, args)
        with patch('publish_cr3_fiji_site.subprocess.run', side_effect=gh):
            with self.assertRaises(subprocess.CalledProcessError):
                attach_archives('owner/repo', 'v0.1.5', Path('.'))
            self.assertTrue(all(args[1] == 'api' for args in calls))
