import gzip
import io
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from publish_cr3_fiji_site import ARTIFACTS, install, previous_archive


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
