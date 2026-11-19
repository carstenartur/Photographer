import gzip
import json
import os
import re
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
from publish_cr3_fiji_site import tagged_source
from publish_cr3_fiji_site import ARTIFACTS, attach_archives, install, previous_archive, verify_served


def archive(root, version, timestamp, example=None):
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
        if example is not None:
            zipped.writestr('open-cr3-color.groovy', example)
    path = root / (timestamp + '.zip')
    path.write_bytes(temp.getvalue())
    return path


class WorkflowRecoveryTest(unittest.TestCase):
    workflow = (builder.ROOT / '.github/workflows/cr3-fiji-distribution.yml').read_text()

    def test_selects_latest_nonexpired_retry_artifact_across_pages(self):
        command = re.search(r'^ *artifact=\$\((.*)\)$', self.workflow, re.MULTILINE).group(1)
        pages = [{'artifacts': [{'name': 'cr3-fiji-distribution-1', 'id': 11, 'expired': False}]},
                 {'artifacts': [{'name': 'cr3-fiji-distribution-2', 'id': 22, 'expired': False},
                                {'name': 'other', 'id': 44, 'expired': False},
                                {'name': 'cr3-fiji-distribution-3', 'id': 33, 'expired': True}]}]
        with tempfile.TemporaryDirectory() as temp:
            gh = Path(temp, 'gh')
            gh.write_text('#!/bin/sh\ncase " $* " in *" --jq "*|*" --template "*|*" --slurp "*) exit 2;; esac\n'
                          'case " $* " in *" --paginate "*) cat;; *) exit 2;; esac\n')
            gh.chmod(0o755)
            for example, expected in [(pages, '22'), ([{'artifacts': []}], '')]:
                result = subprocess.run(['bash', '-euo', 'pipefail', '-c', command], text=True, capture_output=True,
                                        input='\n'.join(map(json.dumps, example)),
                                        env={**os.environ, 'GH_REPO': 'owner/project', 'GITHUB_RUN_ID': '12',
                                             'PATH': temp + os.pathsep + os.environ['PATH']})
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout.strip(), expected)

    def test_manual_recovery_builds_and_attaches_from_resolved_release(self):
        self.assertIn('refs/tags/$RELEASE_TAG^{commit}', self.workflow)
        self.assertIn('inputs.release_tag || inputs.publish_existing_tag', self.workflow)
        self.assertIn('ref: ${{ needs.source.outputs.sha }}', self.workflow)
        self.assertIn('needs: [source, bundle]', self.workflow)
        self.assertIn('needs: [source, attach]', self.workflow)
        self.assertNotIn('if: ${{ !inputs.publish_existing_tag }}', self.workflow)


class TaggedSourceTest(unittest.TestCase):
    def test_off_main_tag_only_changes_maven_version_metadata(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)

            def git(*args):
                return subprocess.check_output(['git', *args], cwd=root, text=True).strip()

            def commit(version, tag):
                (root / 'pom.xml').write_text(f'<project><version>{version}</version><properties>'
                                              f'<revision>{version}</revision></properties><scm><tag>{tag}</tag></scm></project>')
                git('add', '.')
                git('commit', '-qm', version)
                return git('rev-parse', 'HEAD')

            git('init', '-q', '-b', 'main')
            git('config', 'user.email', 'release@example.invalid')
            git('config', 'user.name', 'Release test')
            historical = commit('0.1.5', 'HEAD')
            git('tag', 'v0.1.5')
            snapshot = commit('0.1.6-SNAPSHOT', 'HEAD')
            git('update-ref', 'refs/remotes/origin/main', snapshot)
            stable = commit('0.1.6', 'v0.1.6')
            git('tag', '-a', 'v0.1.6', '-m', 'v0.1.6')
            self.assertEqual(tagged_source(root, 'v0.1.5'), historical)
            self.assertEqual(tagged_source(root, 'v0.1.6'), stable)
            git('switch', '--detach', '-q', snapshot)
            (root / 'unreviewed.py').write_text('additional code')
            commit('0.1.7', 'v0.1.7')
            git('tag', 'v0.1.7')
            with self.assertRaisesRegex(ValueError, 'outside'):
                tagged_source(root, 'v0.1.7')


class BuildInputTest(unittest.TestCase):
    def test_previous_site_accepts_the_versioned_color_example_only(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            previous = archive(root, '0.1.8', '20260101000000', b'// versioned example\n')
            site = root / 'site'
            builder.restore_previous(previous, site, ARTIFACTS)
            self.assertEqual(b'// versioned example\n', (site / 'open-cr3-color.groovy').read_bytes())
            with ZipFile(previous, 'a') as zipped:
                zipped.writestr('unrelated.groovy', '// unexpected')
            with self.assertRaisesRegex(ValueError, 'Unexpected path'):
                builder.restore_previous(previous, root / 'invalid', ARTIFACTS)

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
    def test_publishes_versioned_example_and_rejects_changed_release_retry(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            original = b'// 16-bit color example\n'
            asset = archive(root, '0.1.8', '20260101000000', original)
            install(asset, 'v0.1.8', root)
            install(asset, 'v0.1.8', root)
            example = root / 'fiji/cr3/open-cr3-color.groovy'
            self.assertEqual(original, example.read_bytes())
            modified = archive(root, '0.1.8', '20260101000000', b'// changed example\n')
            with self.assertRaisesRegex(ValueError, 'immutable'):
                install(modified, 'v0.1.8', root)
            self.assertEqual(original, example.read_bytes())

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
            example = site / 'open-cr3-color.groovy'
            example.write_bytes(b'// Fiji example\n')
            previous = root / 'previous.zip'
            previous_archive(site, previous)
            with ZipFile(previous) as old:
                self.assertNotIn('index.html', old.namelist())
                self.assertNotIn('version.txt', old.namelist())
                self.assertNotIn('LICENSE.txt', old.namelist())
                self.assertNotIn(example.name, old.namelist())
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
            self.assertEqual(b'// Fiji example\n', example.read_bytes())
            previous_jar = next((site / 'jars').glob('*-20260101000000'))
            previous_jar.write_bytes(b'damaged')
            with self.assertRaisesRegex(ValueError, 'immutable Fiji payload'):
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

    def test_public_verification_includes_the_versioned_example(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            asset = archive(root, '0.1.5', '20260101000000', b'// color example\n')
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
                    self.assertEqual(10, len(expected))
                    self.assertEqual({(name, None) for name in expected}, set(requests))
                    jar = next((published / 'jars').iterdir())
                    original = jar.read_bytes()
                    jar.write_bytes(original[:-1] + bytes([original[-1] ^ 1]))
                    with self.assertRaisesRegex(ValueError, jar.name):
                        verify_served(site, url, 'v0.1.5')
                    jar.write_bytes(original)
                    example = published / 'open-cr3-color.groovy'
                    example.write_bytes(b'// stale example\n')
                    with self.assertRaisesRegex(ValueError, 'open-cr3-color.groovy'):
                        verify_served(site, url, 'v0.1.5')
                    example.write_bytes((site / example.name).read_bytes())
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
