"""Stage and transport the six Maven runtime libraries on the project's Git branch."""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import hashlib
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from threading import Thread
import xml.etree.ElementTree as ET

from validate_cr3_central_bundle import (
    PRIMARY_SUFFIXES, PUBLIC_ARTIFACTS, PUBLIC_GROUP_ID, PUBLIC_GROUP_PATH, validate_payloads,
)

BRANCH = 'maven-repository'
VERSION = re.compile(r'[0-9]+\.[0-9]+\.[0-9]+')
ROOT = Path(__file__).resolve().parents[1]
STAGE = Path('target/public-maven-repository')
CHECKSUMS = ('md5', 'sha1', 'sha256', 'sha512')


def validate_stage(stage: Path, version: str) -> str:
    """Verify Maven payload and sidecars. Only immutable payload contributes to the handoff digest."""
    if not VERSION.fullmatch(version):
        raise ValueError('release version must be X.Y.Z')
    expected = set()
    digest = hashlib.sha256()
    for artifact in PUBLIC_ARTIFACTS:
        parent = Path(PUBLIC_GROUP_PATH, artifact)
        expected.add(parent / 'maven-metadata.xml')
        expected.update(parent / ('maven-metadata.xml.' + alg) for alg in CHECKSUMS)
        payloads = {}
        for suffix in PRIMARY_SUFFIXES:
            relative = parent / version / (artifact + '-' + version + suffix)
            path = stage / relative
            if not path.is_file() or path.is_symlink():
                raise ValueError(f'missing regular release payload {relative}')
            data = path.read_bytes()
            payloads[suffix] = data
            digest.update(relative.as_posix().encode() + b'\0' + hashlib.sha256(data).digest())
            expected.add(relative)
            for algorithm in CHECKSUMS:
                sidecar = Path(str(relative) + '.' + algorithm)
                expected.add(sidecar)
                file = stage / sidecar
                if file.is_file():
                    if file.read_text().strip() != hashlib.new(algorithm, data).hexdigest():
                        raise ValueError(f'checksum mismatch for {sidecar}')
                elif algorithm in ('sha1', 'md5'):
                    raise ValueError(f'missing Maven checksum {sidecar}')
        validate_payloads(payloads, artifact, version)
    for path in stage.rglob('*'):
        if path.is_symlink() or (path.is_file() and path.relative_to(stage) not in expected):
            raise ValueError(f'unexpected release entry {path}')
    return digest.hexdigest()


def checksums(path: Path):
    data = path.read_bytes()
    for algorithm in CHECKSUMS:
        sidecar = Path(str(path) + '.' + algorithm)
        if sidecar.is_symlink():
            raise ValueError(f'unexpected symlink {sidecar}')
        sidecar.write_text(hashlib.new(algorithm, data).hexdigest(), encoding='ascii')


def merge_release(stage: Path, checkout: Path, version: str):
    validate_stage(stage, version)
    for artifact in PUBLIC_ARTIFACTS:
        parent = checkout / PUBLIC_GROUP_PATH / artifact
        destination = parent / version
        source = stage / PUBLIC_GROUP_PATH / artifact / version
        if any(p.is_symlink() for p in (destination, *destination.parents) if p.is_relative_to(checkout)):
            raise ValueError(f'unexpected symlink at {destination}')
        for suffix in PRIMARY_SUFFIXES:
            checksums(source / (artifact + '-' + version + suffix))
        if destination.exists():
            if (not destination.is_dir() or any(p.is_symlink() or not p.is_file() for p in destination.iterdir())
                    or {p.name: p.read_bytes() for p in source.iterdir()} !=
                       {p.name: p.read_bytes() for p in destination.iterdir()}):
                raise ValueError(f'immutable release differs at {destination}')
        else:
            shutil.copytree(source, destination)
        versions = sorted((p.name for p in parent.iterdir() if p.is_dir() and VERSION.fullmatch(p.name)),
                          key=lambda v: tuple(map(int, v.split('.'))))
        metadata_path = parent / 'maven-metadata.xml'
        if metadata_path.is_symlink():
            raise ValueError(f'unexpected symlink {metadata_path}')
        metadata = ET.Element('metadata')
        ET.SubElement(metadata, 'groupId').text = PUBLIC_GROUP_ID
        ET.SubElement(metadata, 'artifactId').text = artifact
        versioning = ET.SubElement(metadata, 'versioning')
        ET.SubElement(versioning, 'latest').text = versions[-1]
        ET.SubElement(versioning, 'release').text = versions[-1]
        values = ET.SubElement(versioning, 'versions')
        for value in versions:
            ET.SubElement(values, 'version').text = value
        ET.indent(metadata)
        ET.ElementTree(metadata).write(metadata_path, encoding='UTF-8', xml_declaration=True)
        checksums(metadata_path)


def publish(root: Path, stage: Path, version: str, guard):
    """Transport a validated release using the existing origin and a disposable worktree."""
    def git(cwd, *args):
        return subprocess.run(['git', *args], cwd=cwd, text=True, stdout=subprocess.PIPE, check=True).stdout.strip()

    ref = 'refs/heads/' + BRANCH
    current = git(root, 'ls-remote', '--refs', 'origin', ref)
    with tempfile.TemporaryDirectory(prefix='cr3-maven-git-') as tmp:
        checkout = Path(tmp) / 'repo'
        orphan = 'cr3-stage-' + checkout.parent.name
        if current:
            git(root, 'fetch', '--no-tags', 'origin', ref)
            git(root, 'worktree', 'add', '--detach', str(checkout), 'FETCH_HEAD')
        else:
            git(root, 'worktree', 'add', '--orphan', '-b', orphan, str(checkout))
        try:
            merge_release(stage, checkout, version)
            git(checkout, 'add', '--', PUBLIC_GROUP_PATH)
            if git(checkout, 'diff', '--cached', '--name-only'):
                git(checkout, '-c', 'user.name=github-actions[bot]', '-c',
                    'user.email=github-actions[bot]@users.noreply.github.com', '-c', 'commit.gpgsign=false',
                    'commit', '-m', 'Publish CR3 libraries ' + version)
            # A normal fast-forward push rejects a race with another publisher.
            guard()
            git(checkout, 'push', 'origin', 'HEAD:' + ref)
        finally:
            git(root, 'worktree', 'remove', '--force', str(checkout))
            if not current and git(root, 'branch', '--list', orphan):
                git(root, 'branch', '-D', orphan)


@contextmanager
def serve_stage(stage: Path):
    """Record actual artifact transfers independently of Maven Resolver's cache format."""
    served = set()

    class Handler(SimpleHTTPRequestHandler):
        def log_message(self, *args):
            pass  # Maven probes missing metadata and third-party dependencies here.

        def copyfile(self, source, output):
            super().copyfile(source, output)
            path = Path(self.translate_path(self.path))
            if path.is_file():
                served.add(path.relative_to(stage).as_posix())

    with ThreadingHTTPServer(('127.0.0.1', 0), partial(Handler, directory=str(stage))) as server:
        server.daemon_threads = False  # Finish recording every transfer before inspecting the set.
        thread = Thread(target=server.serve_forever)
        thread.start()
        try:
            yield f'http://127.0.0.1:{server.server_port}/', served
        finally:
            server.shutdown()
            thread.join()


def verify_consumer(root: Path, stage: Path, version: str):
    """A fresh independent Maven consumer resolves cr3-imageio and all five siblings."""
    with tempfile.TemporaryDirectory(prefix='cr3-maven-consumer-') as tmp:
        cache = Path(tmp) / 'repository'
        jars = Path(tmp) / 'jars'
        with serve_stage(stage) as (url, served):
            subprocess.run([sys.executable, str(Path(__file__).with_name('run_maven_with_retry.py')),
                            '-B', '-ntp', '-f', str(root / 'release/canon-cr3-consumer/pom.xml'),
                            '-Pphotographer-releases', '-Dphotographer.repository.url=' + url,
                            '-Dcr3.version=' + version, '-Dmaven.repo.local=' + str(cache),
                            '-DoutputDirectory=' + str(jars),
                            'org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy-dependencies'],
                           cwd=root, check=True)
        for artifact in PUBLIC_ARTIFACTS:
            relative = Path(PUBLIC_GROUP_PATH, artifact, version)
            for suffix in ('.pom', '.jar'):
                name = artifact + '-' + version + suffix
                if (cache / relative / name).read_bytes() != (stage / relative / name).read_bytes():
                    raise ValueError(f'consumer resolved different bytes for {name}')
                if (relative / name).as_posix() not in served:
                    raise ValueError(f'consumer did not resolve {name} from the staged repository')
            if not (jars / (artifact + '-' + version + '.jar')).is_file():
                raise ValueError(f'missing transitive dependency {artifact}')


def stage_release(root: Path, version: str):
    if not VERSION.fullmatch(version):
        raise ValueError('release version must be X.Y.Z')
    stage = root / STAGE
    with tempfile.TemporaryDirectory(prefix='cr3-maven-stage-') as tmp:
        fresh = Path(tmp) / 'repository'
        subprocess.run(['mvn', '-B', '-ntp', '-f', 'release/canon-cr3-public/pom.xml',
                        '-Ppublic-repository-release', '-Drevision=' + version,
                        '-Dpublic.repository.directory=' + str(fresh), 'clean', 'deploy'], cwd=root, check=True)
        digest = validate_stage(fresh, version)
        verify_consumer(root, fresh, version)
        stage.parent.mkdir(exist_ok=True)
        if stage.exists():
            shutil.rmtree(stage)
        shutil.copytree(fresh, stage)
        return digest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('version', help='release version X.Y.Z')
    args = parser.parse_args()
    print('Verified Maven release payload SHA-256:', stage_release(ROOT, args.version))
