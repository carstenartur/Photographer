#!/usr/bin/env python3
"""Check the six released Maven Central artifacts against the Git-backed distribution."""
import argparse
from pathlib import Path
import hashlib
import sys
import tempfile
import subprocess
from time import monotonic, sleep
from urllib.error import HTTPError
from urllib.request import urlopen

from validate_cr3_central_bundle import PRIMARY_SUFFIXES, PUBLIC_ARTIFACTS, PUBLIC_GROUP_PATH
from cr3_repository import VERSION

CENTRAL = 'https://repo.maven.apache.org/maven2/'


def state(distribution, version, fetch=urlopen):
    if not VERSION.fullmatch(version):
        raise ValueError('Expected a stable X.Y.Z version')
    present = missing = 0
    for artifact in PUBLIC_ARTIFACTS:
        for suffix in PRIMARY_SUFFIXES:
            name = f'{artifact}-{version}{suffix}'
            path = Path(PUBLIC_GROUP_PATH) / artifact / version / name
            expected = hashlib.sha256((distribution / path).read_bytes()).digest()
            try:
                with fetch(CENTRAL + path.as_posix(), timeout=30) as response:
                    actual = hashlib.file_digest(response, 'sha256').digest()
            except HTTPError as error:
                if error.code != 404:
                    raise
                missing += 1
                continue
            if expected != actual:
                raise ValueError('Immutable Maven Central payload differs from the Git release: ' + name)
            present += 1
    if present and missing:
        return 'partial'
    return 'absent' if missing else 'published'


def verify_consumer(version, distribution):
    with tempfile.TemporaryDirectory(prefix='cr3-central-consumer-') as directory:
        cache = Path(directory, 'cache')
        jars = Path(directory, 'jars')
        subprocess.run(['mvn', '-B', '-ntp', '-f', 'release/canon-cr3-consumer/pom.xml',
                        '-Dmaven.repo.local=' + str(cache), '-DoutputDirectory=' + str(jars),
                        '-Dcr3.version=' + version,
                        'org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy-dependencies'], check=True)
        for artifact in PUBLIC_ARTIFACTS:
            name = f'{artifact}-{version}.jar'
            actual = (jars / name).read_bytes()
            if actual != (distribution / PUBLIC_GROUP_PATH / artifact / version / name).read_bytes():
                raise ValueError('External consumer resolved different bytes for ' + name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('version')
    parser.add_argument('distribution', type=Path)
    parser.add_argument('--wait', action='store_true')
    args = parser.parse_args()
    deadline = monotonic() + 900
    while True:
        status = state(args.distribution, args.version)
        if status == 'published':
            if args.wait:
                verify_consumer(args.version, args.distribution)
            break
        if not args.wait and status == 'absent':
            break
        if monotonic() >= deadline:
            raise RuntimeError(f'Public Maven Central distribution for {args.version} is {status}')
        sleep(20)
    print(status)
    return 0


if __name__ == '__main__':
    sys.exit(main())
