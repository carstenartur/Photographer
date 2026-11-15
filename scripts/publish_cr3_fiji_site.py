#!/usr/bin/env python3
"""Install a verified stable Fiji site archive into a checked-out gh-pages branch."""

import argparse
import filecmp
import gzip
from pathlib import Path
import re
import shutil
import tempfile
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from build_cr3_fiji_site import LIBRARIES, PLUGIN, current_payloads, zip_files

ARTIFACTS = {f'jars/cr3-{suffix}.jar': f'cr3-{suffix}' for suffix in LIBRARIES}
ARTIFACTS[PLUGIN] = 'cr3-imagej'
VERSION = re.compile(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)')
STAMP = re.compile(r'[0-9]{14}')
MARKER = 'version.txt'
ANCILLARY = {MARKER, 'index.html', 'LICENSE.txt'}


def database_history(site):
    with gzip.open(site / 'db.xml.gz') as stream:
        db = ET.parse(stream)
    if db.getroot().tag != 'pluginRecords' or db.findall('update-site') or db.findall('disabled-update-site'):
        raise ValueError('Hosted database must contain only the CR3 update records')
    result = {}
    for plugin in db.findall('plugin'):
        for entry in [plugin.find('version'), *plugin.findall('previous-version')]:
            filename = entry.get('filename', plugin.get('filename'))
            stamp = entry.get('timestamp', '')
            digest = entry.get('checksum', '')
            if filename not in ARTIFACTS or not STAMP.fullmatch(stamp) or not re.fullmatch('[a-f0-9]{40}', digest):
                raise ValueError('Invalid Fiji update history')
            key = filename + '-' + stamp
            if not (site / key).is_file() or key in result:
                raise ValueError('Missing or duplicate timestamped Fiji history: ' + key)
            result[key] = digest
    return result


def previous_archive(site, archive):
    """Extract only updater records and immutable payloads, including initial preview history."""
    current_payloads(site, ARTIFACTS)
    files = {name: site / name for name in database_history(site)}
    zip_files(archive, {**files, 'db.xml.gz': site / 'db.xml.gz'})


def install(archive, tag, checkout):
    version = tag.removeprefix('v')
    if tag != 'v' + version or not VERSION.fullmatch(version):
        raise ValueError('Expected a stable release tag vX.Y.Z')
    destination = checkout / 'fiji' / 'cr3'
    if any(p.is_symlink() for p in (destination.parent, destination)):
        raise ValueError('Refusing a symbolic link in the hosted site path')
    if destination.exists() and any(p.is_symlink() for p in destination.rglob('*')):
        raise ValueError('Refusing a symbolic link inside the hosted site')
    with tempfile.TemporaryDirectory() as temp:
        site = Path(temp)
        with ZipFile(archive) as zipfile:
            names = zipfile.namelist()
            if len(names) != len(set(names)) or 'db.xml.gz' not in names:
                raise ValueError('Missing or duplicate update-site archive entry')
            for name in names:
                if name != 'db.xml.gz':
                    filename, _, stamp = name.rpartition('-')
                    if filename not in ARTIFACTS or not STAMP.fullmatch(stamp):
                        raise ValueError('Unexpected archive path: ' + name)
                zipfile.extract(name, site)
        for name, payload in current_payloads(site, ARTIFACTS).items():
            artifact = ARTIFACTS[name]
            with ZipFile(payload) as jar:
                path = f'META-INF/maven/io.github.carstenartur/{artifact}/pom.properties'
                properties = set(jar.read(path).decode('ascii').splitlines())
                if not {f'version={version}', f'artifactId={artifact}', 'groupId=io.github.carstenartur'} <= properties:
                    raise ValueError(f'{name} is not the release artifact {artifact}:{version}')
        history = database_history(site)
        if set(names) != set(history) | {'db.xml.gz'}:
            raise ValueError('Archive contains payloads outside the Fiji database history')
        marker = destination / MARKER
        if destination.exists():
            previous = marker.read_text().strip()
            previous_core = previous.removesuffix('-SNAPSHOT')
            if not VERSION.fullmatch(previous_core) or tuple(map(int, previous_core.split('.'))) > tuple(map(int, version.split('.'))):
                raise ValueError('Refusing to roll back the published Fiji release')
            unchanged = filecmp.cmp(destination / 'db.xml.gz', site / 'db.xml.gz', shallow=False)
            if previous == version and not unchanged:
                raise ValueError('Published Fiji releases are immutable')
            if not database_history(destination).items() <= history.items():
                raise ValueError('The new Fiji database forks the published history')
            for path in destination.rglob('*'):
                relative = path.relative_to(destination)
                if path.is_file() and relative.as_posix() not in ANCILLARY | {'db.xml.gz'}:
                    new = site / relative
                    if not new.is_file() or not filecmp.cmp(path, new, shallow=False):
                        raise ValueError('Missing or changed immutable Fiji payload: ' + str(path))
        shutil.copytree(site, destination, dirs_exist_ok=True)
        marker.write_text(version + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    publish = commands.add_parser('install')
    publish.add_argument('archive', type=Path)
    publish.add_argument('tag')
    publish.add_argument('pages_checkout', type=Path)
    previous = commands.add_parser('previous')
    previous.add_argument('site', type=Path)
    previous.add_argument('archive', type=Path)
    args = parser.parse_args()
    if args.command == 'previous':
        previous_archive(args.site, args.archive)
    else:
        install(args.archive, args.tag, args.pages_checkout)
