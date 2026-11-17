#!/usr/bin/env python3
"""Release the exact main checkout with Maven, retaining a snapshot-only PR diff."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import posixpath
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from time import monotonic, sleep
import xml.etree.ElementTree as ET
from zipfile import ZipFile

import cr3_repository

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('release_version_plan', ROOT / '.github/scripts/release-version-plan.py')
planner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(planner)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def git(root, *args):
    return subprocess.check_output(['git', *args], cwd=root, text=True).strip()


def maven(root, *args):
    subprocess.run(['mvn', '-B', '-ntp', *args], cwd=root, check=True)


def resolve_versions(current, release_override='', development_override=''):
    require(re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT', current), 'Expected an X.Y.Z-SNAPSHOT project')
    release = release_override or current.removesuffix('-SNAPSHOT')
    plan = planner.resolve_plan(release, development_override)
    return plan['release'], plan['next']


def version_at(root, ref):
    model = ET.fromstring(git(root, 'show', ref + ':pom.xml'))
    version = model.findtext('{*}version')
    require(version == model.findtext('{*}properties/{*}revision'), 'project.version and CR3 revision disagree at ' + ref)
    return version


def reactor_members(root, source):
    pending, found = ['pom.xml'], set()
    while pending:
        path = pending.pop()
        if path in found:
            continue
        found.add(path)
        pom = ET.fromstring(git(root, 'show', source + ':' + path))
        for module in pom.findall('.//{*}modules/{*}module'):
            entry = posixpath.normpath(posixpath.join(posixpath.dirname(path), module.text.strip(), 'pom.xml'))
            require(not entry.startswith(('../', '/')), 'Reactor module escapes repository')
            pending.append(entry)
    return found


def projected_pom(root, source, path, current, target, scm_tag='HEAD'):
    original = subprocess.check_output(['git', 'show', source + ':' + path], cwd=root)
    # The native plugin also normalizes XML layout. Project its verified version
    # values onto the original bytes so the development PR only shows the bump.
    version = re.compile(rb'(<(version|revision)>\s*)' + re.escape(current.encode()) + rb'(\s*</\2>)')
    projected = version.sub(lambda m: m[1] + target.encode() + m[3], original)
    if scm_tag != 'HEAD':
        projected = projected.replace(b'<tag>HEAD</tag>', b'<tag>' + scm_tag.encode() + b'</tag>')
    return projected


def verify_history(root, source, tag, successor, current, release, development):
    """The plugin makes two commits. Its stable tag is the first one."""
    stable = git(root, 'rev-parse', tag + '^{commit}')
    require(git(root, 'show', '-s', '--format=%P', stable) == source, 'The release commit must directly follow the selected main commit')
    require(git(root, 'show', '-s', '--format=%P', successor) == stable, 'The development commit must directly follow the tagged release')
    for ref, expected in [(source, current), (stable, release), (successor, development)]:
        require(version_at(root, ref) == expected, 'Unexpected Maven version at ' + ref)
    members = reactor_members(root, source)
    for before, after, target, scm_tag in [(source, stable, release, tag), (stable, successor, development, 'HEAD')]:
        changes = git(root, 'diff', '--name-status', '--no-renames', before, after).splitlines()
        require(changes and all(line.startswith('M\t') and line[2:] in members for line in changes),
                'Maven release commits may only update existing reactor POMs')
        for line in changes:
            path = line[2:]
            expected = projected_pom(root, source, path, current, target, scm_tag)
            actual = subprocess.check_output(['git', 'show', after + ':' + path], cwd=root)
            canonical = dict(strip_text=True, with_comments=True)
            require(ET.canonicalize(expected, **canonical) == ET.canonicalize(actual, **canonical),
                    'Unexpected Maven model change in ' + path)
    return stable


def remote_ref(root, ref):
    output = git(root, 'ls-remote', '--refs', 'origin', ref)
    if not output:
        return None
    sha, actual = output.split()
    require(actual == ref and re.fullmatch(r'[0-9a-f]{40}', sha), 'Unexpected remote ref')
    return sha


def artifact_hash(path):
    with ZipFile(path) as jar:
        manifest = jar.read('META-INF/MANIFEST.MF').decode('utf-8')
        require('Start-Class:' in manifest and any(n.startswith('BOOT-INF/classes/') for n in jar.namelist()),
                'Expected an executable Spring Boot JAR')
    return hashlib.sha256(path.read_bytes()).hexdigest()


def gh(*args):
    return subprocess.check_output(['gh', *args], text=True).strip()


def wait_for_main_ci(repo, source):
    # A labelled merge starts this workflow alongside its push CI.
    deadline = monotonic() + 3600
    while True:
        runs = json.loads(gh('run', 'list', '--repo', repo, '--workflow', 'ci.yml', '--event', 'push',
                             '--branch', 'main', '--commit', source, '--limit', '1',
                             '--json', 'status,conclusion,url,headSha'))
        if runs and runs[0]['status'] == 'completed':
            run = runs[0]
            require(run['headSha'] == source and run['conclusion'] == 'success', 'Main CI did not pass: ' + run['url'])
            print('Verified main CI:', run['url'])
            return
        require(monotonic() < deadline, 'Timed out waiting for CI on the selected main source')
        sleep(20)


def release_record(repo, tag):
    """Distinguish an absent release from an authentication or API failure."""
    path = f'repos/{repo}/releases/tags/{tag}'
    response = subprocess.run(['gh', 'api', '--include', path], text=True, capture_output=True)
    header, body = re.split(r'\r?\n\r?\n', response.stdout, maxsplit=1)
    status = int(header.split()[1])
    data = json.loads(body)
    if status == 404 and data.get('message') == 'Not Found':
        return None
    response.check_returncode()
    require(status == 200 and data['tag_name'] == tag and not data['draft'] and not data['prerelease'],
            'Unexpected GitHub release state')
    return data


def publish_app(repo, tag, jar, digest):
    release = release_record(repo, tag)
    if release is None:
        gh('release', 'create', tag, '--verify-tag', '--title', f'Release {tag[1:]}',
           '--generate-notes', '--repo', repo)
        release = release_record(repo, tag)
    assets = [a for a in release['assets'] if a['name'] == jar.name]
    if not assets:
        gh('release', 'upload', tag, str(jar), '--repo', repo)
        assets = [a for a in release_record(repo, tag)['assets'] if a['name'] == jar.name]
    require(len(assets) == 1 and assets[0]['state'] == 'uploaded'
            and assets[0].get('digest') == 'sha256:' + digest, 'Existing application asset differs from the verified build')


def existing_snapshot_pr(repo, branch):
    # Branch names alone are not unique across forks.
    prs = json.loads(gh('pr', 'list', '--repo', repo, '--base', 'main', '--head', branch,
                        '--state', 'all', '--json', 'state,url,headRepository,headRepositoryOwner'))
    for pr in prs:
        owner = (pr.get('headRepositoryOwner') or {}).get('login', '')
        name = (pr.get('headRepository') or {}).get('name', '')
        if (owner + '/' + name).lower() == repo.lower() and pr['state'] in ('OPEN', 'MERGED'):
            return pr
    return None


def snapshot_pr(repo, branch, release, development):
    previous = existing_snapshot_pr(repo, branch)
    if previous:
        return previous['url']
    return gh('pr', 'create', '--repo', repo, '--base', 'main', '--head', branch,
              '--title', f'Begin development of {development}',
              '--body', f'Maven prepared release `v{release}`. The net change against main advances the project to `{development}`.')


def run(root, source, release_override, development_override, dry_run):
    require(os.environ.get('GITHUB_REF') == 'refs/heads/main' and re.fullmatch(r'[a-f0-9]{40}', source),
            'Select a main commit for release')
    require(git(root, 'rev-parse', 'HEAD') == source, 'Checkout does not match the workflow source')
    git(root, 'merge-base', '--is-ancestor', source, 'refs/remotes/origin/main')
    require(not git(root, 'status', '--porcelain', '--untracked-files=no'), 'Release checkout is dirty')
    current = version_at(root, source)
    release, development = resolve_versions(current, release_override, development_override)
    tag = 'v' + release
    tag_ref = 'refs/tags/' + tag
    branch = f'release/next-{development}-{source[:12]}'
    branch_ref = 'refs/heads/' + branch
    remote_tag = remote_ref(root, tag_ref)
    remote_branch = remote_ref(root, branch_ref)

    # Regenerating both commits locally also validates a retry against its original source.
    if remote_tag:
        git(root, 'fetch', '--no-tags', 'origin', tag_ref)
        require(git(root, 'rev-parse', 'FETCH_HEAD') == remote_tag, 'The remote tag changed')
        if subprocess.run(['git', 'show-ref', '--verify', '--quiet', tag_ref], cwd=root).returncode == 0:
            git(root, 'tag', '-d', tag)
    git(root, 'switch', '--create', 'maven-release-work', source)
    git(root, 'config', 'user.name', 'github-actions[bot]')
    git(root, 'config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com')
    timestamp = git(root, 'show', '-s', '--format=%cI', source)
    os.environ.update(GIT_AUTHOR_DATE=timestamp, GIT_COMMITTER_DATE=timestamp)
    maven(root, '-Pbenchmarks', 'release:prepare', '-Dresume=false', '-DpushChanges=false',
          '-DreleaseVersion=' + release, '-DdevelopmentVersion=' + development, '-Dtag=' + tag)
    successor = git(root, 'rev-parse', 'HEAD')
    stable = verify_history(root, source, tag, successor, current, release, development)
    metadata = git(root, 'diff', '--name-only', source, successor).splitlines()
    for path in metadata:
        (root / path).write_bytes(projected_pom(root, source, path, current, development))
    if git(root, 'diff', '--name-only'):
        git(root, 'add', '--', *metadata)
        git(root, 'commit', '--amend', '--no-edit')
        successor = git(root, 'rev-parse', 'HEAD')
    # A local tag by the same name never authorizes replacing an existing remote one.
    if remote_tag:
        require(git(root, 'rev-parse', remote_tag + '^{commit}') == stable,
                'This version already belongs to a different release commit')
        git(root, 'update-ref', tag_ref, remote_tag)
    require(remote_branch in (None, successor), 'Existing snapshot branch has a different development commit')

    # Perform checks the stable tag out afresh. Its Maven deploy target is the
    # six-library public reactor and a local Maven repository, including on previews.
    with tempfile.TemporaryDirectory(prefix='photographer-release-') as directory:
        temp = Path(directory)
        checkout, stage = temp / 'checkout', temp / 'repository'
        maven(root, 'release:perform', '-DlocalCheckout=true',
              '-DworkingDirectory=' + str(checkout), '-Dgoals=deploy',
              '-Darguments=-ntp -f release/canon-cr3-public/pom.xml -Ppublic-repository-release'
              + ' -Dpublic.repository.directory=' + str(stage))
        require(git(checkout, 'rev-parse', 'HEAD') == stable, 'release:perform checked out a different commit')
        cr3_repository.validate_stage(stage, release)
        cr3_repository.verify_consumer(checkout, stage, release)
        maven(checkout, '-Dproject.build.outputTimestamp=' + timestamp, 'clean', 'verify')
        jar = checkout / 'app/target/focusstack.jar'
        digest = artifact_hash(jar)
        result = dict(stable_sha=stable, release_tag=tag, development_version=development, snapshot_branch=branch)
        if dry_run:
            return result
        repo = os.environ['GITHUB_REPOSITORY']
        # Atomic, non-forced refs. No release operation pushes main.
        refs = []
        if not remote_tag:
            wait_for_main_ci(repo, source)
            require(remote_ref(root, 'refs/heads/main') == source, 'Main advanced before the first publication; dispatch again from its current head')
            refs.append(tag_ref + ':' + tag_ref)
        previous = existing_snapshot_pr(repo, branch) if remote_tag and not remote_branch else None
        if not remote_branch and not (previous and previous['state'] == 'MERGED'):
            refs.append(successor + ':' + branch_ref)
        maintenance_ref = 'refs/heads/' + planner.resolve_plan(release, development)['maintenance_branch']
        if not remote_ref(root, maintenance_ref):
            refs.append(stable + ':' + maintenance_ref)
        if refs:
            # Each ref in this list was absent. A racing writer must win or lose the
            # creation as a whole, even if their branch happens to be our ancestor.
            leases = ['--force-with-lease=' + ref.split(':')[1] + ':' for ref in refs]
            git(root, 'push', '--atomic', *leases, 'origin', *refs)

        def guard():
            require(remote_ref(root, tag_ref) == git(root, 'rev-parse', tag_ref), 'The release tag changed')

        guard()
        cr3_repository.publish(root, stage, release, guard)
        guard()
        publish_app(repo, tag, jar, digest)
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release-version', default='')
    parser.add_argument('--development-version', default='')
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('--open-snapshot', metavar='BRANCH')
    args = parser.parse_args()
    try:
        if args.open_snapshot:
            expected = f"release/next-{args.development_version}-{os.environ['GITHUB_SHA'][:12]}"
            require(args.open_snapshot == expected, 'Unexpected snapshot branch')
            url = snapshot_pr(os.environ['GITHUB_REPOSITORY'], expected, args.release_version, args.development_version)
            with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
                print(url, file=summary)
            print(url)
            return 0
        outputs = run(ROOT, os.environ['GITHUB_SHA'], args.release_version, args.development_version, args.dry_run)
        for key, value in outputs.items():
            require('\n' not in value and '\r' not in value, 'Invalid workflow output')
            print(f'{key}={value}')
        with open(os.environ['GITHUB_OUTPUT'], 'a') as out:
            print(*(f'{k}={v}' for k, v in outputs.items()), sep='\n', file=out)
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
            print(f"{outputs['release_tag']} at `{outputs['stable_sha']}`, next development version `{outputs['development_version']}`.", file=summary)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f'::error::{error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
