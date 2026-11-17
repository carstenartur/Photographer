"""Reuse immutable GHCR releases and advance floating tags by published version."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def versions(tags):
    return [tuple(map(int, tag.split('.'))) for tag in tags if re.fullmatch(r'\d+\.\d+\.\d+', tag)]


def eligible_aliases(version, published):
    current, = versions([version])
    for length in (2, 1, 0):
        if not any(v > current and v[:length] == current[:length] for v in published):
            yield '.'.join(version.split('.')[:length]) if length else 'latest'


def inspect(reference):
    return json.loads(subprocess.check_output(['skopeo', 'inspect', '--no-tags', reference], text=True))


def run(mode, image, version, revision):
    repository = 'docker://' + image
    listing = subprocess.run(['skopeo', 'list-tags', repository], capture_output=True, text=True)
    if listing.returncode and 'name unknown' in listing.stderr.lower():
        tags = set()
    else:
        if listing.returncode:
            raise RuntimeError(listing.stderr.strip())
        tags = set(json.loads(listing.stdout)['Tags'])

    published = inspect(repository + ':' + version) if version in tags else None
    if published and (published['Labels'] or {}).get('org.opencontainers.image.revision') != revision:
        raise ValueError(f'{image}:{version} references a different source commit')
    if mode == 'check':
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            print('exists=' + ('true' if published else 'false'), file=output)
            if published:
                print('digest=' + published['Digest'], file=output)
        return

    if not published or published['Digest'] != os.environ['EXPECTED_DIGEST']:
        raise ValueError(f'{image}:{version} does not match the expected digest')
    digest = published['Digest']
    for alias in eligible_aliases(version, versions(tags)):
        existing = inspect(repository + ':' + alias) if alias in tags else None
        if existing and existing['Digest'] == digest:
            continue
        if existing and alias == 'latest':
            # The main-branch Docker workflow also publishes latest. Preserve a
            # branch image built after this release's source when retrying.
            labels = existing['Labels'] or {}
            if not versions([labels.get('org.opencontainers.image.version', '')]):
                previous = labels.get('org.opencontainers.image.revision', '')
                if not re.fullmatch(r'[a-f0-9]{40}', previous):
                    continue
                ancestry = subprocess.run(['git', 'merge-base', '--is-ancestor', previous, revision])
                if ancestry.returncode == 1:
                    continue
                ancestry.check_returncode()
        subprocess.run(['skopeo', 'copy', '--all', '--preserve-digests',
                        repository + '@' + digest, repository + ':' + alias], check=True)


if __name__ == '__main__':
    if sys.argv[1:] not in (['check'], ['promote']):
        raise SystemExit('usage: release_docker.py check|promote')
    docker_config = Path(os.environ.get('DOCKER_CONFIG', Path.home() / '.docker'))
    os.environ.setdefault('REGISTRY_AUTH_FILE', str(docker_config / 'config.json'))
    run(sys.argv[1], os.environ['IMAGE_NAME'].lower(), os.environ['RELEASE_TAG'].removeprefix('v'),
        os.environ['STABLE_SHA'])
